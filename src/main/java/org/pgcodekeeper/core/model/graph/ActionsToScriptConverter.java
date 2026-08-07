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
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IColumn;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IForeignTable;
import org.pgcodekeeper.core.database.api.schema.ISequence;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.api.schema.ObjectState;
import org.pgcodekeeper.core.database.base.schema.AbstractStatement;
import org.pgcodekeeper.core.database.ms.schema.MsColumn;
import org.pgcodekeeper.core.database.ms.schema.MsConstraintPk;
import org.pgcodekeeper.core.database.ms.schema.MsTable;
import org.pgcodekeeper.core.database.ms.schema.MsView;
import org.pgcodekeeper.core.database.ms.utils.MsDiffUtils;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgPartitionTable;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSequence;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeElement.DiffSide;
import org.pgcodekeeper.core.script.SQLActionType;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.PhaseTimer;
import org.pgcodekeeper.core.utils.Utils;

/**
 * Converts database action containers into executable SQL script statements.
 * Processes CREATE, ALTER, and DROP operations in proper dependency order while handling
 * special cases like data movement, table renaming, and partition tables.
 *
 * <p>This class is responsible for generating the final SQL migration script from
 * resolved database actions. It handles complex scenarios including:
 * <ul>
 * <li>Data movement mode with temporary table creation and renaming</li>
 * <li>Joinable column alterations that can be combined into single ALTER TABLE statements</li>
 * <li>Partition table hierarchies and their dependencies</li>
 * <li>Microsoft SQL module refresh operations</li>
 * <li>Identity column preservation during data movement</li>
 * </ul>
 *
 * <p>The converter processes actions in dependency order and applies filtering
 * based on user selections and configured object type restrictions.
 */
public final class ActionsToScriptConverter {

    private static final String REFRESH_MODULE = "EXEC sys.sp_refreshsqlmodule %s";

    private static final String DROP_COMMENT = "-- DEPCY: This %s %s depends on the %s: %s";
    private static final String CREATE_COMMENT = "-- DEPCY: This %s %s is a dependency of %s: %s";
    private static final String HIDDEN_OBJECT = "-- HIDDEN: Object %s of type %s (action: %s, reason: %s)";

    /**
     * Joins the names of a path, see {@link #namePath(TreeElement)}.
     * <p>
     * A database that keeps its identifiers as C strings cannot hold this
     * character in one. Nothing rests on that anyway: two different paths
     * joining to one string would only leave an extra element under a key, and
     * the identity check in {@link #isSelectedObject} turns that element down
     * like any other that is not this object.
     */
    private static final char NAME_PATH_SEPARATOR = '\0';

    private final SQLScript script;
    private final ISettings settings;
    private final Set<ActionContainer> actions;
    private final Map<IStatement, SQLScript> alterScripts;
    private final Set<IStatement> toRefresh;
    private final IDatabase oldDbFull;
    private final IDatabase newDbFull;

    /**
     * The selection indexed by type and name path, and the list it was built
     * from. See {@link #selectionIndex(List)}.
     */
    private Map<DbObjType, Map<String, List<TreeElement>>> selectionIndex;
    private List<TreeElement> indexedSelection;

    private final Map<ActionContainer, List<ActionContainer>> joinableTableActions = new HashMap<>();
    private final Set<ActionContainer> toSkip = new HashSet<>();
    private final Set<IStatement> droppedObjects = new HashSet<>();
    private final Set<PgSequence> earlyOwnedByDetaches = new HashSet<>();
    private final Set<PgSequence> lateOwnedByAttaches =
            new TreeSet<>(Comparator.comparing(PgSequence::getQualifiedName));
    private final Map<PgSequence, PgSequence> lateSequenceAlters =
            new TreeMap<>(Comparator.comparing(PgSequence::getQualifiedName));

    /**
     * renamed table qualified names and their temporary (simple) names
     */
    private Map<String, String> tblTmpNames;
    /**
     * old tables q-names (before rename) and their identity columns' names
     */
    private Map<String, List<String>> tblIdentityCols;

    /**
     * map where key - parent table and values - children tables
     */
    private Map<String, List<PgPartitionTable>> partitionTables;

    /**
     * qualified names of every partition of a moved table, asked only by
     * {@code contains}, hence a set and not a list
     */
    private Set<String> partitionChildren;
    private Map<ObjectReference, LinkedHashSet<PgSequence>> sequencesByOwningTable;

    /**
     * Fills the SQL script with statements based on resolved database actions.
     * Creates a new ActionsToScriptConverter instance and processes all actions in right order.
     *
     * @param script    the SQL script to populate with generated statements
     * @param actions   set of resolved action containers representing database changes
     * @param toRefresh set of statements that need refreshing (for Microsoft SQL modules)
     * @param oldDbFull the complete old database schema for reference
     * @param newDbFull the complete new database schema for reference
     * @param selected  list of user-selected tree elements for filtering actions
     */
    public static void fillScript(SQLScript script,
                                  Set<ActionContainer> actions, Set<IStatement> toRefresh,
                                  IDatabase oldDbFull, IDatabase newDbFull, List<TreeElement> selected) {
        long start = PhaseTimer.start();
        new ActionsToScriptConverter(script, actions, toRefresh, oldDbFull, newDbFull).fillScript(selected);
        PhaseTimer.end("script_convert", start);
    }

    /**
     * Runs the safety check that is still required when dependency resolution
     * intentionally suppresses an owned-sequence DROP in favor of PostgreSQL's
     * implicit cascade. Keeping this check before the empty-action fast return
     * prevents a selected sequence removal from silently becoming an empty script.
     */
    public static void validateEmptyActions(IDatabase oldDatabase, IDatabase newDatabase,
                                            List<TreeElement> selected) {
        selected.stream()
                .filter(element -> element.getType() == DbObjType.SEQUENCE)
                .filter(element -> element.getSide() == DiffSide.LEFT)
                .map(element -> element.getStatement(oldDatabase))
                .filter(PgSequence.class::isInstance)
                .map(PgSequence.class::cast)
                .filter(sequence -> sequence.getTwin(newDatabase) == null)
                .filter(sequence -> sequence.getOwnedBy() != null)
                .filter(sequence -> newDatabase.getStatement(sequence.getOwnedBy()) == null)
                .findFirst()
                .ifPresent(sequence -> {
                    throwRemovedOwnedSequenceRequiresCascade(sequence);
                });
    }

    /**
     * Fills the SQL script with statements based on resolved database actions,
     * reusing the ALTER scripts memoized by {@link DepcyResolver} during state
     * evaluation. The memoized scripts must originate from a resolve run over
     * the same databases and settings as this conversion, which guarantees
     * byte-identical output to rebuilding them.
     *
     * @param script    the SQL script to populate with generated statements
     * @param resolved  actions and memoized ALTER scripts from {@link DepcyResolver#resolveActions}
     * @param toRefresh set of statements that need refreshing (for Microsoft SQL modules)
     * @param oldDbFull the complete old database schema for reference
     * @param newDbFull the complete new database schema for reference
     * @param selected  list of user-selected tree elements for filtering actions
     */
    public static void fillScript(SQLScript script,
                                  DepcyResolver.ResolvedActions resolved, Set<IStatement> toRefresh,
                                  IDatabase oldDbFull, IDatabase newDbFull, List<TreeElement> selected) {
        long start = PhaseTimer.start();
        new ActionsToScriptConverter(script, resolved.actions(), resolved.alterScripts(),
                toRefresh, oldDbFull, newDbFull).fillScript(selected);
        PhaseTimer.end("script_convert", start);
    }

    /**
     * Creates a new ActionsToScriptConverter with the specified parameters.
     * Initializes internal structures for data movement mode if enabled in settings.
     *
     * @param script    the SQL script to populate with generated statements
     * @param actions   set of resolved action containers representing database changes
     * @param toRefresh ordered set of statements requiring refresh operations (in reverse order)
     * @param oldDbFull the complete old database schema for reference and data movement
     * @param newDbFull the complete new database schema for reference and data movement
     */
    public ActionsToScriptConverter(SQLScript script, Set<ActionContainer> actions,
                                    Set<IStatement> toRefresh, IDatabase oldDbFull, IDatabase newDbFull) {
        this(script, actions, Map.of(), toRefresh, oldDbFull, newDbFull);
    }

    /**
     * Creates a new ActionsToScriptConverter with memoized ALTER scripts.
     * Initializes internal structures for data movement mode if enabled in settings.
     *
     * @param script       the SQL script to populate with generated statements
     * @param actions      set of resolved action containers representing database changes
     * @param alterScripts ALTER scripts memoized by {@link DepcyResolver}, keyed by old statement
     * @param toRefresh    ordered set of statements requiring refresh operations (in reverse order)
     * @param oldDbFull    the complete old database schema for reference and data movement
     * @param newDbFull    the complete new database schema for reference and data movement
     */
    public ActionsToScriptConverter(SQLScript script, Set<ActionContainer> actions,
                                    Map<IStatement, SQLScript> alterScripts,
                                    Set<IStatement> toRefresh, IDatabase oldDbFull, IDatabase newDbFull) {
        this.script = script;
        this.actions = actions;
        this.alterScripts = alterScripts;
        this.toRefresh = toRefresh;
        this.oldDbFull = oldDbFull;
        this.newDbFull = newDbFull;
        this.settings = script.getSettings();
        if (settings.isDataMovementMode()) {
            tblTmpNames = new HashMap<>();
            tblIdentityCols = new HashMap<>();
            partitionTables = new HashMap<>();
            partitionChildren = new HashSet<>();
            sequencesByOwningTable = new HashMap<>();
        }
    }

    /**
     * Fills the script with database objects based on their dependency order.
     *
     * @param selected list of user-selected tree elements for filtering actions
     */
    private void fillScript(List<TreeElement> selected) {
        if (settings.isDataMovementMode()) {
            fillDataMovementSequences(selected);
        }
        validateOwnedSequenceDropSafety(selected);
        validateOwnedSequencePrerequisites(selected);
        earlyOwnedByDetaches.stream()
                .sorted(Comparator.comparing(PgSequence::getQualifiedName))
                .forEachOrdered(sequence ->
                        script.addStatement(getOwnedByNoneSql(sequence), SQLActionType.BEGIN));

        Set<IStatement> refreshed = new HashSet<>(toRefresh.size());
        if (settings.isDataMovementMode()) {
            fillPartitionTables();
        }

        fillJoinableTableActions();
        for (ActionContainer action : actions) {
            if (toSkip.contains(action)) {
                continue;
            }

            var obj = action.getOldObj();

            if (toRefresh.contains(obj)) {
                if (action.getState() == ObjectState.CREATE && obj instanceof MsView) {
                    // emit refreshes for views only
                    // refreshes for other objects serve as markers
                    // that allow us to skip unmodified drop+create pairs
                    script.addStatement(REFRESH_MODULE.formatted(
                            Utils.quoteString(obj.getQualifiedName())));
                    refreshed.add(obj);
                }
            } else if (!hideAction(action, selected)) {
                printAction(action, obj);
            }
        }

        // As a result of discussion with the SQL database developers, it was
        // decided that, in pgCodeKeeper, refresh operations are required only
        // for MsView objects. This is why a filter is used here that only
        // leaves refresh operations for MsView objects.
        //
        // if any refreshes were not emitted as statement replacements
        // add them explicitly in reverse order (the resolver adds them in "drop order")
        IStatement[] orphanRefreshes = toRefresh.stream()
                .filter(r -> r instanceof MsView && !refreshed.contains(r))
                .toArray(IStatement[]::new);
        for (int i = orphanRefreshes.length - 1; i >= 0; --i) {
            script.addStatement(REFRESH_MODULE.formatted(
                    Utils.quoteString(orphanRefreshes[i].getQualifiedName())));
        }
        lateSequenceAlters.forEach((oldSequence, newSequence) ->
                script.addAllStatements(getSequenceAlterWithoutOwnedByChange(
                        oldSequence, newSequence)));
        lateOwnedByAttaches.forEach(sequence ->
                sequence.getOwnedBySQL(script, SQLActionType.END));
    }

    /**
     * Collects joinable table actions that can be joined into single ALTER TABLE statements.
     */
    private void fillJoinableTableActions() {
        List<List<ActionContainer>> changedColumnTables = new ArrayList<>();
        String previousParent = null;
        List<ActionContainer> currentList = null;
        for (ActionContainer action : actions) {
            var oldObj = action.getOldObj();
            if (action.getState() == ObjectState.ALTER && oldObj instanceof PgColumn oldCol
                    && oldCol.isJoinable((PgColumn) action.getNewObj(), settings)) {
                String parent = oldObj.getParent().getQualifiedName();
                if (!parent.equals(previousParent)) {
                    currentList = new ArrayList<>();
                    changedColumnTables.add(currentList);
                    previousParent = parent;
                }

                currentList.add(action);
            } else {
                previousParent = null;
            }
        }

        // filling joinableTableActions map where:
        //   key - first action
        //   value - all joinable actions for table
        for (List<ActionContainer> tableChanges : changedColumnTables) {
            if (tableChanges.size() == 1) {
                continue;
            }
            boolean isFirst = true;
            for (ActionContainer action : tableChanges) {
                if (isFirst) {
                    joinableTableActions.put(action, tableChanges);
                    isFirst = false;
                } else {
                    toSkip.add(action);
                }
            }
        }
    }

    private void printAction(ActionContainer action, IStatement obj) {
        String depcy = getComment(action, obj);
        switch (action.getState()) {
            case CREATE:
                if (depcy != null) {
                    script.addStatementWithoutSeparator(depcy);
                }

                var oldObj = obj.getTwin(oldDbFull);

                if (settings.isDataMovementMode() && obj instanceof PgSequence && oldObj != null) {
                    break;
                }

                if (settings.isDropBeforeCreate() && obj.canDropBeforeCreate()) {
                    addToDropScript(obj, true);
                }

                addToAddScript(obj);

                if (settings.isDataMovementMode() && oldObj instanceof ITable oldTable) {
                    moveData(oldTable, obj);
                }
                break;
            case DROP:
                if (depcy != null) {
                    script.addStatementWithoutSeparator(depcy);
                }
                if (settings.isDataMovementMode()
                        && obj instanceof ITable table
                        && !(obj instanceof IForeignTable)
                        && obj.getTwin(newDbFull) != null) {
                    addCommandsForRenameTbl(table);
                } else {
                    checkMsTableOptions(obj);
                    addToDropScript(obj, false);
                }
                break;
            case ALTER:
                var joinableActions = joinableTableActions.get(action);
                if (joinableActions != null) {
                    getAlterTableScript(joinableActions);
                    return;
                }

                // reuse the ALTER script memoized by the resolver, if present;
                // it was built from the same statements, settings and separator
                SQLScript alterScript = alterScripts.get(obj);
                if (alterScript == null) {
                    alterScript = new SQLScript(script.getSettings(), obj.getSeparator());
                    ObjectState state = obj.appendAlterSQL(action.getNewObj(), alterScript);
                    if (!state.in(ObjectState.ALTER, ObjectState.ALTER_WITH_DEP)) {
                        break;
                    }
                }

                if (obj instanceof PgSequence oldSequence
                        && action.getNewObj() instanceof PgSequence newSequence
                        && earlyOwnedByDetaches.contains(oldSequence)) {
                    if (newSequence.getOwnedBy() == null) {
                        alterScript = getSequenceAlterWithoutOwnedByChange(
                                oldSequence, newSequence);
                    }
                }

                if (depcy != null) {
                    script.addStatementWithoutSeparator(depcy);
                }
                script.addAllStatements(alterScript);
                break;
            default:
                throw new IllegalStateException(Messages.ActionsToScriptConverter_not_implemented_action);
        }
    }

    private SQLScript getSequenceAlterWithoutOwnedByChange(PgSequence oldSequence,
                                                           PgSequence newSequence) {
        var adjustedTarget = (PgSequence) newSequence.shallowCopy();
        adjustedTarget.setOwnedBy(oldSequence.getOwnedBy());
        var schema = new PgSchema(newSequence.getSchemaName());
        schema.addChild(adjustedTarget);

        var adjustedScript = new SQLScript(script.getSettings(), newSequence.getSeparator());
        oldSequence.appendAlterSQL(adjustedTarget, adjustedScript);
        return adjustedScript;
    }

    private static String getOwnedByNoneSql(PgSequence sequence) {
        return "ALTER SEQUENCE " + sequence.getQualifiedName() + "\n\tOWNED BY NONE";
    }

    private void checkMsTableOptions(IStatement obj) {
        if (obj instanceof MsConstraintPk && obj.getParent() instanceof MsTable oldTable) {
            MsTable newTable = (MsTable) oldTable.getTwin(newDbFull);
            if (oldTable.compare(newTable)) {
                oldTable.compareTableOptions(newTable, script);
            }
        }
    }

    private void addToAddScript(IStatement obj) {
        obj.getCreationSQL(script);
    }

    private void addToDropScript(IStatement obj, boolean isExist) {
        // check "drop before create"
        var oldObj = obj.getTwin(oldDbFull);
        if (oldObj != null && !droppedObjects.add(oldObj)) {
            return;
        }
        obj.getDropSQL(script, isExist);
    }

    /**
     * Generates ALTER TABLE script with all joinable changes.
     *
     * @param actionsList list of joinable action containers for the same table
     */
    private void getAlterTableScript(List<ActionContainer> actionsList) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (var colAction : actionsList) {
            PgColumn oldNextCol = (PgColumn) colAction.getOldObj();
            PgColumn newNextCol = (PgColumn) colAction.getNewObj();
            oldNextCol.joinAction(sb, newNextCol, i == 1, i == actionsList.size(), settings);
            i++;
        }
        script.addStatement(sb);
    }

    private void fillPartitionTables() {
        for (ActionContainer action : actions) {
            var obj = action.getOldObj();

            if (action.getState() == ObjectState.CREATE && obj instanceof PgPartitionTable table) {
                partitionTables.computeIfAbsent(table.getParentTable(), tables -> new ArrayList<>()).add(table);
            }
        }

        Iterator<Entry<String, List<PgPartitionTable>>> iterator = partitionTables.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry<String, List<PgPartitionTable>> next = iterator.next();
            String parent = next.getKey();
            for (Entry<String, List<PgPartitionTable>> partitions : partitionTables.entrySet()) {
                List<PgPartitionTable> tables = partitions.getValue();
                if (tables.stream().map(AbstractStatement::getQualifiedName).anyMatch(el -> el.equals(parent))) {
                    tables.addAll(next.getValue());
                    iterator.remove();
                    break;
                }
            }
        }

        partitionChildren = partitionTables.values().stream()
                .flatMap(List::stream)
                .map(AbstractStatement::getQualifiedName)
                .collect(Collectors.toUnmodifiableSet());
    }

    private void fillDataMovementSequences(List<TreeElement> selected) {
        Set<IStatement> alteredSequences = actions.stream()
                .filter(action -> action.getState() == ObjectState.ALTER)
                .filter(action -> action.getOldObj() instanceof PgSequence oldSequence
                        && action.getNewObj() instanceof PgSequence newSequence
                        && (!Objects.equals(oldSequence.getOwnedBy(), newSequence.getOwnedBy())
                                || !Objects.equals(oldSequence.getOwner(), newSequence.getOwner())))
                .filter(action -> isActionEmitted(action, selected))
                .map(ActionContainer::getNewObj)
                .collect(Collectors.toSet());

        Set<String> movingTables = actions.stream()
                .filter(action -> action.getState() == ObjectState.DROP)
                .filter(action -> action.getOldObj() instanceof ITable
                        && !(action.getOldObj() instanceof IForeignTable))
                .filter(action -> action.getOldObj().getTwin(newDbFull) != null)
                .filter(action -> isActionEmitted(action, selected))
                .map(ActionContainer::getOldObj)
                .map(IStatement::getQualifiedName)
                .collect(Collectors.toSet());

        newDbFull.getDescendants()
                .filter(PgSequence.class::isInstance)
                .map(PgSequence.class::cast)
                .filter(sequence -> sequence.getTwin(oldDbFull) != null)
                .filter(sequence -> !alteredSequences.contains(sequence))
                .filter(sequence -> sequence.getOwnedBy() != null)
                .filter(sequence -> {
                    IStatement owningTable = getOwningTable(newDbFull, sequence.getOwnedBy());
                    return owningTable != null
                            && movingTables.contains(owningTable.getQualifiedName());
                })
                .filter(sequence -> isObjectRequested(sequence, selected))
                .sorted(Comparator.comparing(PgSequence::getQualifiedName))
                .forEachOrdered(sequence -> sequencesByOwningTable
                        .computeIfAbsent(getOwningTableReference(sequence.getOwnedBy()),
                                key -> new LinkedHashSet<>())
                        .add(sequence));
    }

    private void moveData(ITable oldTable, IStatement newObj) {
        String qname = newObj.getQualifiedName();
        String tempName = tblTmpNames.get(qname);
        if (tempName == null) {
            return;
        }

        List<PgPartitionTable> tables = partitionTables.get(qname);
        if (tables != null) {
            // print create for partition tables
            for (PgPartitionTable table : tables) {
                addToAddScript(table);
            }
        }

        oldTable.appendMoveDataSql(newObj, script, tempName, tblIdentityCols.get(qname));

        //add OWNED BY if table have sequence
        var tableSequences = sequencesByOwningTable.get(
                new ObjectReference(oldTable.getSchemaName(), oldTable.getBareName(),
                        DbObjType.TABLE));
        if (tableSequences != null) {
            for (var seq : tableSequences) {
                seq.getOwnedBySQL(script, SQLActionType.MID);
            }
        }

        if (tables != null) {
            List<PgPartitionTable> list = new ArrayList<>(tables);
            Collections.reverse(list);
            list.forEach(this::printDropTempTable);
        }

        printDropTempTable(oldTable);
    }

    private void printDropTempTable(ITable table) {
        String tblTmpName = tblTmpNames.get(table.getQualifiedName());
        if (tblTmpName != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("DROP TABLE ")
                    .append(table.getParent().getQuotedName()).append('.').append(table.quote(tblTmpName));
            script.addStatement(sb);
        }
    }

    private String getComment(ActionContainer action, IStatement oldObj) {
        IStatement objStarter = action.getStarter();
        if (objStarter == null || objStarter == oldObj || objStarter == action.getNewObj()) {
            return null;
        }

        // skip column to parent
        if (objStarter.getStatementType() == DbObjType.COLUMN && objStarter.getParent().equals(oldObj)) {
            return null;
        }

        // skip partition tables in data move mode
        if (settings.isDataMovementMode() && partitionChildren.contains(oldObj.getQualifiedName())) {
            return null;
        }

        return (action.getState() == ObjectState.CREATE ?
                CREATE_COMMENT : DROP_COMMENT).formatted(
                oldObj.getStatementType(),
                oldObj.getBareName(),
                objStarter.getStatementType(),
                objStarter.getQualifiedName());
    }

    /**
     * Determines whether an action should be hidden from the generated script.
     * Checks various conditions including object drop capability, user selection mode,
     * and allowed object types configuration.
     *
     * @param action   the action container to evaluate
     * @param selected list of user-selected tree elements
     * @return true if action should be hidden, false if it may be executed
     */
    private boolean hideAction(ActionContainer action, List<TreeElement> selected) {
        var obj = action.getOldObj();
        if (action.getState() == ObjectState.DROP && !obj.canDrop()) {
            addHiddenObj(action, "object cannot be dropped");
            return true;
        }
        if (settings.isSelectedOnly() && !isSelectedAction(action, selected)) {
            addHiddenObj(action, "cannot change unselected objects in selected-only mode");
            return true;
        }

        DbObjType type = getFilterType(obj);
        if (!isAllowedActionType(action)) {
            if (settings.isStopNotAllowed()) {
                throw new NotAllowedObjectException(Messages.ActionsToScriptConverter_not_allowed_object
                        .formatted(action.getOldObj().getQualifiedName(), type));
            }
            addHiddenObj(action, "object type is not in allowed types list");
            return true;
        }

        return false;
    }

    private void validateOwnedSequenceDropSafety(List<TreeElement> selected) {
        Set<IStatement> emittedDrops = actions.stream()
                .filter(action -> action.getState() == ObjectState.DROP)
                .filter(action -> isActionEmitted(action, selected))
                .map(ActionContainer::getOldObj)
                .filter(object -> object.getStatementType().in(DbObjType.COLUMN, DbObjType.TABLE))
                .collect(Collectors.toSet());

        validateRequestedRemovedSequences(emittedDrops, selected);
        if (emittedDrops.isEmpty()) {
            return;
        }

        Map<IStatement, ActionContainer> sequenceAlters = new HashMap<>();
        Map<PgSequence, ActionContainer> emittedSequenceDrops = new HashMap<>();
        Map<PgSequence, ActionContainer> emittedSequenceCreates = new HashMap<>();
        for (ActionContainer action : actions) {
            if (action.getState() == ObjectState.ALTER
                    && action.getOldObj() instanceof PgSequence oldSequence
                    && oldSequence.getDatabase() == oldDbFull) {
                sequenceAlters.put(action.getOldObj(), action);
            } else if (action.getState() == ObjectState.DROP
                    && action.getOldObj() instanceof PgSequence oldSequence
                    && isActionEmitted(action, selected)) {
                emittedSequenceDrops.put(oldSequence, action);
            } else if (action.getState() == ObjectState.CREATE
                    && action.getNewObj() instanceof PgSequence newSequence
                    && isActionEmitted(action, selected)) {
                emittedSequenceCreates.put(newSequence, action);
            }
        }

        oldDbFull.getDescendants()
                .filter(PgSequence.class::isInstance)
                .map(PgSequence.class::cast)
                .filter(sequence -> sequence.getTwin(newDbFull) != null)
                .sorted(Comparator.comparing(PgSequence::getQualifiedName))
                .forEachOrdered(oldSequence -> validateSurvivingSequenceDrop(oldSequence,
                        emittedDrops, sequenceAlters, emittedSequenceDrops,
                        emittedSequenceCreates, selected));
    }

    private void validateSurvivingSequenceDrop(PgSequence oldSequence,
                                                Set<IStatement> emittedDrops,
                                                Map<IStatement, ActionContainer> sequenceAlters,
                                                Map<PgSequence, ActionContainer> emittedSequenceDrops,
                                                Map<PgSequence, ActionContainer> emittedSequenceCreates,
                                                List<TreeElement> selected) {
        ObjectReference oldOwnedBy = oldSequence.getOwnedBy();
        PgSequence newSequence = (PgSequence) oldSequence.getTwin(newDbFull);
        if (oldOwnedBy == null || newSequence == null
                || !containsOwningDrop(oldOwnedBy, emittedDrops)) {
            return;
        }

        ActionContainer alterAction = sequenceAlters.get(oldSequence);
        if (settings.isDataMovementMode()) {
            if (emittedSequenceDrops.containsKey(oldSequence)) {
                throw new NotAllowedObjectException(
                        Messages.ActionsToScriptConverter_surviving_owned_sequence_requires_recreate
                                .formatted(oldSequence.getQualifiedName(), oldOwnedBy.getFullName()));
            }
            if (alterAction != null
                    && (!Objects.equals(oldOwnedBy, newSequence.getOwnedBy())
                            || !Objects.equals(oldSequence.getOwner(), newSequence.getOwner()))) {
                if (isActionEmitted(alterAction, selected)) {
                    earlyOwnedByDetaches.add(oldSequence);
                    return;
                }
                throw new NotAllowedObjectException(
                        Messages.ActionsToScriptConverter_surviving_owned_sequence_requires_detach
                                .formatted(oldSequence.getQualifiedName(), oldOwnedBy.getFullName()));
            }
            if (newSequence.getOwnedBy() != null
                    && !containsDataMovementSequence(newSequence)) {
                throw new NotAllowedObjectException(
                        Messages.ActionsToScriptConverter_surviving_owned_sequence_requires_recreate
                                .formatted(oldSequence.getQualifiedName(), oldOwnedBy.getFullName()));
            }
            if (newSequence.getOwnedBy() != null) {
                boolean targetTableRecreated = emittedDrops.contains(
                        getOwningTable(oldDbFull, newSequence.getOwnedBy()));
                String targetOwner = newSequence.getOwner();
                if (targetOwner != null) {
                    validateTargetOwningTableOwner(newSequence.getOwnedBy(), targetOwner,
                            selected, newSequence.getQualifiedName(), targetTableRecreated);
                }
                validateUnknownSurvivingSequenceOwner(
                        oldSequence, newSequence, targetTableRecreated, true, selected);
            }
            if (!Objects.equals(oldOwnedBy, newSequence.getOwnedBy())) {
                earlyOwnedByDetaches.add(oldSequence);
            }
            return;
        }

        ActionContainer dropAction = emittedSequenceDrops.get(oldSequence);
        ActionContainer createAction = emittedSequenceCreates.get(newSequence);
        if (dropAction != null || createAction != null) {
            if (dropAction != null && createAction != null
                    && Objects.equals(oldSequence.getQualifiedName(),
                            newSequence.getQualifiedName())) {
                scheduleSequencePreservation(oldSequence, newSequence,
                        dropAction, createAction, emittedDrops, selected);
                return;
            }

            throw new NotAllowedObjectException(
                    Messages.ActionsToScriptConverter_surviving_owned_sequence_requires_recreate
                            .formatted(oldSequence.getQualifiedName(), oldOwnedBy.getFullName()));
        }

        if (alterAction != null
                && (!Objects.equals(oldOwnedBy, newSequence.getOwnedBy())
                        || !Objects.equals(oldSequence.getOwner(), newSequence.getOwner()))) {
            if (isActionEmitted(alterAction, selected)) {
                earlyOwnedByDetaches.add(oldSequence);
                return;
            }

            throw new NotAllowedObjectException(
                    Messages.ActionsToScriptConverter_surviving_owned_sequence_requires_detach
                            .formatted(oldSequence.getQualifiedName(), oldOwnedBy.getFullName()));
        }

        if (Objects.equals(oldOwnedBy, newSequence.getOwnedBy())
                && Objects.equals(oldSequence.getOwner(), newSequence.getOwner())
                && isObjectRequested(oldSequence, selected)
                && (alterAction == null || isActionEmitted(alterAction, selected))) {
            String targetOwner = newSequence.getOwner();
            boolean owningTableDropped = emittedDrops.contains(
                    getOwningTable(oldDbFull, oldOwnedBy));
            if (targetOwner != null) {
                validateTargetOwningTableOwner(newSequence.getOwnedBy(), targetOwner, selected,
                        newSequence.getQualifiedName(), owningTableDropped);
            }
            validateTargetOwningColumn(newSequence.getOwnedBy(), selected,
                    newSequence.getQualifiedName(), true);
            validateUnknownSurvivingSequenceOwner(
                    oldSequence, newSequence, owningTableDropped, true, selected);
            if (alterAction == null) {
                lateSequenceAlters.put(oldSequence, newSequence);
            }
            earlyOwnedByDetaches.add(oldSequence);
            lateOwnedByAttaches.add(newSequence);
            return;
        }

        throw new NotAllowedObjectException(
                Messages.ActionsToScriptConverter_surviving_owned_sequence_requires_recreate
                        .formatted(oldSequence.getQualifiedName(), oldOwnedBy.getFullName()));
    }

    private void scheduleSequencePreservation(PgSequence oldSequence,
                                              PgSequence newSequence,
                                              ActionContainer dropAction,
                                              ActionContainer createAction,
                                              Set<IStatement> emittedDrops,
                                              List<TreeElement> selected) {
        ObjectReference targetOwnedBy = newSequence.getOwnedBy();
        if (targetOwnedBy != null) {
            IStatement oldTargetTable = getOwningTable(oldDbFull, targetOwnedBy);
            boolean targetTableDropped = oldTargetTable != null
                    && emittedDrops.contains(oldTargetTable);
            String targetOwner = newSequence.getOwner();
            if (targetOwner != null) {
                validateTargetOwningTableOwner(targetOwnedBy, targetOwner, selected,
                        newSequence.getQualifiedName(), targetTableDropped);
            }

            boolean targetColumnRequiresCreation = oldDbFull.getStatement(targetOwnedBy) == null
                    || containsOwningDrop(targetOwnedBy, emittedDrops);
            validateTargetOwningColumn(targetOwnedBy, selected,
                    newSequence.getQualifiedName(), targetColumnRequiresCreation);
            validateUnknownSurvivingSequenceOwner(
                    oldSequence, newSequence, targetTableDropped, true, selected);
            lateOwnedByAttaches.add(newSequence);
        }

        toSkip.add(dropAction);
        toSkip.add(createAction);
        earlyOwnedByDetaches.add(oldSequence);
        lateSequenceAlters.put(oldSequence, newSequence);
    }

    private void validateRequestedRemovedSequences(Set<IStatement> emittedDrops,
                                                   List<TreeElement> selected) {
        selected.stream()
                .filter(element -> element.getType() == DbObjType.SEQUENCE)
                .filter(element -> element.getSide() == DiffSide.LEFT)
                .map(element -> element.getStatement(oldDbFull))
                .filter(PgSequence.class::isInstance)
                .map(PgSequence.class::cast)
                .forEach(sequence -> validateRemovedSequence(sequence, emittedDrops));
    }

    private void validateRemovedSequence(PgSequence oldSequence,
                                         Set<IStatement> emittedDrops) {
        ObjectReference oldOwnedBy = oldSequence.getOwnedBy();
        if (oldOwnedBy == null || oldSequence.getTwin(newDbFull) != null
                || newDbFull.getStatement(oldOwnedBy) != null
                || containsOwningDrop(oldOwnedBy, emittedDrops)) {
            return;
        }

        throwRemovedOwnedSequenceRequiresCascade(oldSequence);
    }

    private boolean containsOwningDrop(ObjectReference ownedBy,
                                       Set<IStatement> emittedDrops) {
        IStatement oldColumn = oldDbFull.getStatement(ownedBy);
        IStatement oldTable = getOwningTable(oldDbFull, ownedBy);
        return emittedDrops.contains(oldColumn) || emittedDrops.contains(oldTable);
    }

    private boolean isObjectRequested(IStatement statement, List<TreeElement> selected) {
        Collection<DbObjType> allowedTypes = settings.getAllowedTypes();
        if (!allowedTypes.isEmpty() && !allowedTypes.contains(getFilterType(statement))) {
            return false;
        }
        return !settings.isSelectedOnly() || isSelectedObject(statement, selected);
    }

    private static void throwRemovedOwnedSequenceRequiresCascade(PgSequence sequence) {
        throw new NotAllowedObjectException(
                removedOwnedSequenceRequiresCascadeMessage(sequence, sequence.getOwnedBy()));
    }

    private static String removedOwnedSequenceRequiresCascadeMessage(
            PgSequence sequence, ObjectReference ownedBy) {
        return Messages.ActionsToScriptConverter_removed_owned_sequence_requires_cascade
                .formatted(sequence.getQualifiedName(), ownedBy.getFullName());
    }

    private void validateOwnedSequencePrerequisites(List<TreeElement> selected) {
        for (ActionContainer action : actions) {
            if (!isActionEmitted(action, selected)) {
                continue;
            }

            if (action.getState() == ObjectState.CREATE
                    && action.getNewObj() instanceof PgSequence createdSequence
                    && createdSequence.getTwin(oldDbFull) == null) {
                validateCreatedOwnedSequence(createdSequence, selected);
                continue;
            }

            if (action.getState() != ObjectState.ALTER
                    || !(action.getOldObj() instanceof PgSequence oldSequence)
                    || !(action.getNewObj() instanceof PgSequence newSequence)) {
                continue;
            }

            ObjectReference oldOwnedBy = oldSequence.getOwnedBy();
            ObjectReference newOwnedBy = newSequence.getOwnedBy();
            boolean ownerChanged = !Objects.equals(oldSequence.getOwner(), newSequence.getOwner());
            boolean ownedByChanged = !Objects.equals(oldOwnedBy, newOwnedBy);
            boolean forcedSameReferenceReattach = newOwnedBy != null
                    && Objects.equals(oldOwnedBy, newOwnedBy)
                    && earlyOwnedByDetaches.contains(oldSequence);
            if (!ownerChanged && !ownedByChanged) {
                continue;
            }

            String targetOwner = newSequence.getOwner();
            if (targetOwner != null) {
                if (ownerChanged && oldOwnedBy != null && newOwnedBy != null
                        && !earlyOwnedByDetaches.contains(oldSequence)) {
                    validateCurrentOwningTableOwner(oldOwnedBy, targetOwner, selected,
                            oldSequence.getQualifiedName());
                }

                if ((ownedByChanged || forcedSameReferenceReattach) && newOwnedBy != null) {
                    validateTargetOwningTableOwner(newOwnedBy, targetOwner, selected,
                            oldSequence.getQualifiedName());
                }
            }

            if ((ownedByChanged || forcedSameReferenceReattach) && newOwnedBy != null) {
                validateTargetOwningColumn(newOwnedBy, selected,
                        oldSequence.getQualifiedName(), forcedSameReferenceReattach);
            }
            if (newOwnedBy != null && targetOwner == null
                    && (ownedByChanged || forcedSameReferenceReattach)) {
                validateUnknownSurvivingSequenceOwner(oldSequence, newSequence,
                        isOwningTableDropEmitted(newOwnedBy, selected),
                        earlyOwnedByDetaches.contains(oldSequence), selected);
            }
            if (forcedSameReferenceReattach) {
                lateOwnedByAttaches.add(newSequence);
            }
        }
    }

    private void validateCreatedOwnedSequence(PgSequence sequence,
                                              List<TreeElement> selected) {
        ObjectReference ownedBy = sequence.getOwnedBy();
        if (ownedBy == null) {
            return;
        }

        String targetOwner = sequence.getOwner();
        if (targetOwner != null) {
            validateTargetOwningTableOwner(ownedBy, targetOwner, selected,
                    sequence.getQualifiedName());
        }
        validateTargetOwningColumn(ownedBy, selected,
                sequence.getQualifiedName(), false);
        if (targetOwner == null) {
            IStatement targetTable = getOwningTable(newDbFull, ownedBy);
            boolean createdByCurrentRole = targetTable != null
                    && targetTable.getOwner() == null
                    && hasEmittedTableCreation(targetTable, selected);
            if (!createdByCurrentRole) {
                throwUnknownOwnedSequenceOwner(sequence.getQualifiedName(), ownedBy);
            }
        }
    }

    private void validateCurrentOwningTableOwner(ObjectReference ownedBy, String targetOwner,
                                                 List<TreeElement> selected, String sequenceName) {
        IStatement oldTable = getOwningTable(oldDbFull, ownedBy);
        IStatement newTable = oldTable == null ? null : oldTable.getTwin(newDbFull);
        if (oldTable != null && newTable != null
                && Objects.equals(newTable.getOwner(), targetOwner)
                && hasEmittedOwnerAction(oldTable, newTable, targetOwner, selected)) {
            return;
        }

        throwMissingOwningTableOwner(sequenceName, ownedBy, oldTable, targetOwner);
    }

    private void validateTargetOwningTableOwner(ObjectReference ownedBy, String targetOwner,
                                                List<TreeElement> selected, String sequenceName) {
        validateTargetOwningTableOwner(ownedBy, targetOwner, selected, sequenceName, false);
    }

    private void validateTargetOwningTableOwner(ObjectReference ownedBy, String targetOwner,
                                                List<TreeElement> selected, String sequenceName,
                                                boolean requireEmittedOwnerAction) {
        IStatement newTable = getOwningTable(newDbFull, ownedBy);
        IStatement oldTable = newTable == null ? null : newTable.getTwin(oldDbFull);
        boolean ownerAlreadyReady = oldTable != null && Objects.equals(oldTable.getOwner(), targetOwner);
        if (newTable != null && Objects.equals(newTable.getOwner(), targetOwner)
                && ((!requireEmittedOwnerAction && ownerAlreadyReady)
                        || hasEmittedOwnerAction(oldTable, newTable, targetOwner, selected))) {
            return;
        }

        throwMissingOwningTableOwner(sequenceName, ownedBy, newTable, targetOwner);
    }

    private static IStatement getOwningTable(IDatabase database, ObjectReference ownedBy) {
        return database.getStatement(getOwningTableReference(ownedBy));
    }

    private static ObjectReference getOwningTableReference(ObjectReference ownedBy) {
        return new ObjectReference(ownedBy.schema(), ownedBy.table(), DbObjType.TABLE);
    }

    private void validateUnknownSurvivingSequenceOwner(PgSequence oldSequence,
                                                       PgSequence newSequence,
                                                       boolean targetTableRecreated,
                                                       boolean sequenceDetached,
                                                       List<TreeElement> selected) {
        ObjectReference targetOwnedBy = newSequence.getOwnedBy();
        if (newSequence.getOwner() != null || targetOwnedBy == null) {
            return;
        }

        ObjectReference oldOwnedBy = oldSequence.getOwnedBy();
        IStatement targetOwningTable = getOwningTable(newDbFull, targetOwnedBy);
        IStatement oldTargetOwningTable = targetOwningTable == null
                ? null : targetOwningTable.getTwin(oldDbFull);
        boolean targetTableCreated = targetTableRecreated
                || oldTargetOwningTable == null;
        boolean sameExistingTable = oldOwnedBy != null
                && Objects.equals(getOwningTableReference(oldOwnedBy),
                        getOwningTableReference(targetOwnedBy))
                && oldTargetOwningTable != null
                && !targetTableCreated
                && !sequenceDetached;
        if (sameExistingTable) {
            return;
        }

        String effectiveSequenceOwner = oldSequence.getOwner();
        if (sequenceDetached && effectiveSequenceOwner == null && oldOwnedBy != null) {
            IStatement oldOwningTable = getOwningTable(oldDbFull, oldOwnedBy);
            effectiveSequenceOwner = oldOwningTable == null
                    ? null : oldOwningTable.getOwner();
        } else if (!sequenceDetached && oldOwnedBy != null) {
            String effectiveSourceOwner = getEffectiveExistingTableOwner(
                    oldOwnedBy, selected);
            if (effectiveSourceOwner != null) {
                effectiveSequenceOwner = effectiveSourceOwner;
            }
        }

        String effectiveTargetOwner = targetTableCreated
                ? getEffectiveCreatedTableOwner(targetOwningTable, selected)
                : getEffectiveExistingTableOwner(targetOwnedBy, selected);
        if (effectiveSequenceOwner != null
                && Objects.equals(effectiveSequenceOwner, effectiveTargetOwner)) {
            return;
        }
        if (effectiveSequenceOwner != null && targetOwningTable != null
                && Objects.equals(effectiveSequenceOwner,
                        targetOwningTable.getOwner())) {
            validateTargetOwningTableOwner(targetOwnedBy, effectiveSequenceOwner, selected,
                    newSequence.getQualifiedName(), targetTableCreated);
            return;
        }

        throwUnknownOwnedSequenceOwner(newSequence.getQualifiedName(), targetOwnedBy);
    }

    private String getEffectiveExistingTableOwner(ObjectReference ownedBy,
                                                  List<TreeElement> selected) {
        IStatement newTable = getOwningTable(newDbFull, ownedBy);
        IStatement oldTable = newTable == null ? null : newTable.getTwin(oldDbFull);
        if (oldTable == null) {
            return null;
        }

        String oldOwner = oldTable.getOwner();
        String targetOwner = newTable.getOwner();
        return targetOwner != null
                && hasEmittedOwnerAction(oldTable, newTable, targetOwner, selected)
                ? targetOwner
                : oldOwner;
    }

    private String getEffectiveCreatedTableOwner(IStatement newTable,
                                                 List<TreeElement> selected) {
        if (newTable == null || newTable.getOwner() == null) {
            return null;
        }

        IStatement oldTable = newTable.getTwin(oldDbFull);
        return hasEmittedOwnerAction(oldTable, newTable, newTable.getOwner(), selected)
                ? newTable.getOwner()
                : null;
    }

    private boolean isOwningTableDropEmitted(ObjectReference ownedBy,
                                             List<TreeElement> selected) {
        IStatement oldTable = getOwningTable(oldDbFull, ownedBy);
        return oldTable != null && actions.stream()
                .filter(action -> action.getState() == ObjectState.DROP)
                .filter(action -> Objects.equals(action.getOldObj(), oldTable))
                .anyMatch(action -> isActionEmitted(action, selected));
    }

    private static void throwUnknownOwnedSequenceOwner(String sequenceName,
                                                       ObjectReference ownedBy) {
        throw new NotAllowedObjectException(
                Messages.ActionsToScriptConverter_owned_sequence_requires_known_owner
                        .formatted(sequenceName,
                                ownedBy.schema() + '.' + ownedBy.table()));
    }

    private boolean containsDataMovementSequence(PgSequence sequence) {
        ObjectReference ownedBy = sequence.getOwnedBy();
        if (ownedBy == null) {
            return false;
        }

        Set<PgSequence> tableSequences = sequencesByOwningTable.get(
                getOwningTableReference(ownedBy));
        return tableSequences != null && tableSequences.contains(sequence);
    }

    private void validateTargetOwningColumn(ObjectReference ownedBy,
                                            List<TreeElement> selected, String sequenceName,
                                            boolean requireEmittedCreation) {
        IStatement newColumn = newDbFull.getStatement(ownedBy);
        if (newColumn != null && ((!requireEmittedCreation
                && oldDbFull.getStatement(ownedBy) != null)
                || hasEmittedColumnCreation(newColumn, selected))) {
            return;
        }

        throw new NotAllowedObjectException(
                Messages.ActionsToScriptConverter_owned_sequence_requires_column
                        .formatted(sequenceName, ownedBy.getFullName()));
    }

    private boolean hasEmittedColumnCreation(IStatement newColumn,
                                             List<TreeElement> selected) {
        for (ActionContainer action : actions) {
            if (action.getState() == ObjectState.CREATE
                    && (Objects.equals(action.getNewObj(), newColumn)
                            || Objects.equals(action.getNewObj(), newColumn.getParent()))
                    && isActionEmitted(action, selected)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEmittedTableCreation(IStatement newTable,
                                            List<TreeElement> selected) {
        return actions.stream()
                .filter(action -> action.getState() == ObjectState.CREATE)
                .filter(action -> Objects.equals(action.getNewObj(), newTable))
                .filter(action -> !toSkip.contains(action))
                .anyMatch(action -> isActionEmitted(action, selected));
    }

    private static void throwMissingOwningTableOwner(String sequenceName, ObjectReference ownedBy,
                                                     IStatement table, String targetOwner) {
        String tableName = table == null
                ? ownedBy.schema() + '.' + ownedBy.table()
                : table.getQualifiedName();
        throw new NotAllowedObjectException(
                Messages.ActionsToScriptConverter_owned_sequence_requires_table_owner
                        .formatted(sequenceName, tableName, targetOwner));
    }

    private boolean hasEmittedOwnerAction(IStatement oldTable, IStatement newTable,
                                          String targetOwner, List<TreeElement> selected) {
        for (ActionContainer action : actions) {
            boolean matches = action.getState() == ObjectState.CREATE
                    ? Objects.equals(action.getNewObj(), newTable)
                    : action.getState() == ObjectState.ALTER
                            && oldTable != null
                            && Objects.equals(action.getOldObj(), oldTable)
                            && Objects.equals(action.getNewObj(), newTable)
                            && !Objects.equals(oldTable.getOwner(), targetOwner);
            if (matches && Objects.equals(action.getNewObj().getOwner(), targetOwner)
                    && isActionEmitted(action, selected)) {
                return true;
            }
        }
        return false;
    }

    private boolean isActionEmitted(ActionContainer action, List<TreeElement> selected) {
        if (toRefresh.contains(action.getOldObj())) {
            return action.getState() == ObjectState.CREATE
                    && action.getOldObj() instanceof MsView;
        }
        return (action.getState() != ObjectState.DROP || action.getOldObj().canDrop())
                && (!settings.isSelectedOnly() || isSelectedAction(action, selected))
                && isAllowedActionType(action);
    }

    private boolean isAllowedActionType(ActionContainer action) {
        Collection<DbObjType> allowedTypes = settings.getAllowedTypes();
        return allowedTypes.isEmpty()
                || allowedTypes.contains(getFilterType(action.getOldObj()));
    }

    private static DbObjType getFilterType(IStatement statement) {
        DbObjType type = statement.getStatementType();
        return type == DbObjType.COLUMN ? DbObjType.TABLE : type;
    }

    private void addHiddenObj(ActionContainer action, String reason) {
        var old = action.getOldObj();
        String message = HIDDEN_OBJECT.formatted(
                old.getQualifiedName(), old.getStatementType(), action.getState(), reason);
        script.addStatement(message);
    }

    /**
     * Determines whether an action object has been selected in the diff panel.
     *
     * @param action   script action element
     * @param selected collection of selected elements in diff panel
     * @return true if the action object was selected in the diff panel, false otherwise
     */
    private boolean isSelectedAction(ActionContainer action, List<TreeElement> selected) {
        return switch (action.getState()) {
            case CREATE -> isSelectedObject(action.getNewObj(), selected);
            case ALTER -> isSelectedObject(action.getNewObj(), selected)
                    && isSelectedObject(action.getOldObj(), selected);
            case DROP -> isSelectedObject(action.getOldObj(), selected);
        default -> throw new IllegalStateException(Messages.ActionsToScriptConverter_not_implemented_action);
        };
    }

    private boolean isSelectedObject(IStatement object, List<TreeElement> selected) {
        for (TreeElement e : selectionIndex(selected)
                .getOrDefault(object.getStatementType(), Collections.emptyMap())
                .getOrDefault(namePath(object), Collections.emptyList())) {
            if (object.equals(e.findStatement(object.getDatabase()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Indexes the selection by type and by the path of names that leads to an
     * element, once per selection list.
     * <p>
     * Selected-only mode asks whether an object is in the selection once per
     * action, in each of several passes over the actions. Answering that by
     * walking the whole selection makes the conversion quadratic in the size of
     * the selection: on a full creation script, where every object of the
     * project is at once an action and a selected element, that walk was
     * measured at 3.4 billion element visits and a hundred seconds of
     * conversion. The index answers out of two hash lookups.
     * <p>
     * The key is what {@link AbstractStatement#equals} requires of the two
     * before it can call them equal, and no more: the same type, the same name,
     * and the same names all the way up, which is what
     * {@code AbstractStatement.parentNamesEquals} checks. An element keyed
     * differently could never have been this object, so leaving it unvisited
     * costs no answer - and it saves the walk that keying by the simple name
     * alone still paid, 111 million object comparisons of it on that same full
     * creation script, because a column named {@code id} shares its simple name
     * with every other table's.
     * <p>
     * What the key does not carry is the type of each container on the way up,
     * because equality does not ask for it either: an object kept as a table on
     * one side and rebuilt as a view on the other has the same name path, and
     * their same-named children land here together. Which of them is this object
     * is then decided the only way it can be, by asking each element for its
     * object in this very database - see {@link TreeElement#findStatement}.
     * <p>
     * The list is not mutated once a conversion has it, and the index is
     * rebuilt anyway if a different list arrives.
     */
    private Map<DbObjType, Map<String, List<TreeElement>>> selectionIndex(List<TreeElement> selected) {
        if (selectionIndex == null || indexedSelection != selected) {
            Map<DbObjType, Map<String, List<TreeElement>>> index = new EnumMap<>(DbObjType.class);
            for (TreeElement e : selected) {
                index.computeIfAbsent(e.getType(), t -> new HashMap<>())
                        .computeIfAbsent(namePath(e), n -> new ArrayList<>())
                        .add(e);
            }
            selectionIndex = index;
            indexedSelection = selected;
        }
        return selectionIndex;
    }

    /**
     * The name of an element and the names of its containers, joined bottom up.
     * <p>
     * The topmost holder is left out on purpose. A tree element hangs off a node
     * that stands for the database itself and carries a name of its own, while a
     * statement hangs off the database it was loaded into; leaving both out keeps
     * the two paths comparable and costs nothing, because
     * {@code AbstractStatement.parentNamesEquals} compares that level too and
     * decides it for us.
     */
    private static String namePath(TreeElement element) {
        StringBuilder path = new StringBuilder();
        path.append(element.getName()).append(NAME_PATH_SEPARATOR);
        for (TreeElement p = element.getParent(); p != null && p.getParent() != null; p = p.getParent()) {
            path.append(p.getName()).append(NAME_PATH_SEPARATOR);
        }
        return path.toString();
    }

    /**
     * The same path for a statement, see {@link #namePath(TreeElement)}.
     */
    private static String namePath(IStatement object) {
        StringBuilder path = new StringBuilder();
        path.append(object.getName()).append(NAME_PATH_SEPARATOR);
        for (IStatement p = object.getParent(); p != null && p.getParent() != null; p = p.getParent()) {
            path.append(p.getName()).append(NAME_PATH_SEPARATOR);
        }
        return path.toString();
    }

    /**
     * Adds commands to the script for rename the original table name to a
     * temporary name, given the constraints. Fills the maps {@link #tblTmpNames}
     * and {@link #tblIdentityCols} for use them later (when adding commands to
     * move data from a temporary table to a new table).
     *
     * @param oldTbl the original table to be renamed to a temporary name
     */
    private void addCommandsForRenameTbl(ITable oldTbl) {
        String qname = oldTbl.getQualifiedName();
        String tmpTblName = getTempName(oldTbl);

        script.addStatement(oldTbl.getRenameCommand(tmpTblName));
        tblTmpNames.put(qname, tmpTblName);

        List<String> identityCols = new ArrayList<>();
        for (IColumn col : oldTbl.getColumns()) {
            if (col instanceof PgColumn oldPgCol) {
                PgColumn newPgCol = (PgColumn) oldPgCol.getTwin(newDbFull);
                if (newPgCol != null && newPgCol.getSequence() != null) {
                    ISequence seq = oldPgCol.getSequence();
                    if (seq != null) {
                        script.addStatement(seq.getRenameCommand(getTempName(seq)));
                    }
                    identityCols.add(oldPgCol.getName());
                }
            } else if (col instanceof MsColumn msCol) {
                if (msCol.isIdentity()) {
                    identityCols.add(msCol.getName());
                }
                if (msCol.getDefaultName() != null) {
                    script.addStatement("ALTER TABLE "
                            + MsDiffUtils.quoteName(oldTbl.getParent().getName()) + '.'
                            + MsDiffUtils.quoteName(tmpTblName) + " DROP CONSTRAINT "
                            + MsDiffUtils.quoteName(msCol.getDefaultName()));
                }
            }
        }

        if (!identityCols.isEmpty()) {
            tblIdentityCols.put(qname, identityCols);
        }
    }

    private String getTempName(IStatement st) {
        String tmpSuffix = '_' + UUID.randomUUID().toString().replace("-", "");
        String name = st.getName();
        if (name.length() > 30) {
            return name.substring(0, 30) + tmpSuffix;
        }

        return name + tmpSuffix;
    }
}
