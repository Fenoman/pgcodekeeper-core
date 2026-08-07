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

import java.util.LinkedHashSet;
import java.util.Set;

import org.pgcodekeeper.core.database.api.schema.IColumn;
import org.pgcodekeeper.core.database.api.schema.ISequence;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * The values of one object a comparison passes over, so that whoever reads that
 * comparison can be told which lines those are.
 * <p>
 * <b>Two reasons, and they are not the same reason.</b> Two settings drop a
 * value that a script can express perfectly well - it is the operator who
 * declares the difference uninteresting, see
 * {@link ISettings#isIgnoreSequenceCache()} and
 * {@link ISettings#isIgnoreColumnStatistics()}. The value is not thereby
 * removed: it stays in the project files, and a database created from them gets
 * exactly what the project declares. What is dropped is the
 * <em>difference</em> between two states of it, so a migration between two
 * existing states carries none. Against that stands a collation the migration
 * cannot express, which no setting governs and no script will ever carry, see
 * {@link IColumn#differsOnlyInUnmigratableCollation}. Both end up as lines to
 * mark and each keeps its own {@link SqlMark}, because a reader who cannot tell
 * a preference from a permanent fact has been told the wrong thing.
 * <p>
 * <b>Why the statistics target is worth marking at all.</b> A reader looking at
 * a statistics target that differs between the two sides has no way of telling
 * whether the migration is about to change it. Told that it is not, the reader
 * knows something more useful still: an empty database raised from this project
 * will get exactly the target the project states, because that is the one the
 * project owns and the export keeps, see {@code PgColumn.adoptedStatistics}.
 * Which side of the comparison the project happens to be on does not enter into
 * it.
 * <p>
 * <b>Only where the difference is really dropped.</b> A value the migration is
 * about to write must not be marked - that would be a lie of exactly the kind
 * the marking exists to prevent - and every reason above is about a
 * <em>difference</em>, which needs two states to exist at all. A sequence, a
 * column or an identity that only one of the two sides holds is created or
 * dropped whole, cache and statistics target and collation and all, so nothing
 * about it is overlooked and nothing about it is marked. That is what every
 * field below comes down to: the carrier of the value, present on both sides.
 *
 * @param sequenceCache      whether the cache clause of a sequence that is an
 *                           object of its own is overlooked here
 * @param cacheColumns       the columns whose identity sequence has its cache
 *                           overlooked; a sequence written inside the definition
 *                           of its column rather than as an object of its own,
 *                           see {@link IColumn#hasIdentitySequence()}
 * @param statisticsColumns  the columns whose statistics target is overlooked
 * @param collationColumns   the columns whose whole difference is a collation
 *                           no migration script can express, see
 *                           {@link IColumn#differsOnlyInUnmigratableCollation}
 * @see SqlMarkup for finding the clauses these name in a rendering
 */
public record IgnoredValues(boolean sequenceCache, Set<String> cacheColumns, Set<String> statisticsColumns,
        Set<String> collationColumns) {

    /** Nothing is passed over, which is the answer for very nearly every object there is. */
    public static final IgnoredValues NONE = new IgnoredValues(false, Set.of(), Set.of(), Set.of());

    /**
     * What a comparison passes over about one object shown with both of its
     * states.
     * <p>
     * The two settings are read first because they are a field each, and the
     * columns are walked only where something can come of it: the collation is
     * looked for whatever the settings say - no setting governs it - but the
     * walk is over the columns of one object, each of them answering from a
     * field it already holds, and it stops at the first cheap question for
     * almost every column there is.
     *
     * @param settings settings of the comparison, may be {@code null}
     * @param oldState the state a migration starts from, {@code null} when that
     *                 side holds none
     * @param newState the state a migration produces, {@code null} when that
     *                 side holds none
     * @return the values to mark, {@link #NONE} whenever there is nothing to
     * pass over or one of the two states is missing
     */
    public static IgnoredValues of(ISettings settings, IStatement oldState, IStatement newState) {
        if (settings == null || oldState == null || newState == null) {
            return NONE;
        }

        boolean cache = settings.isIgnoreSequenceCache();
        boolean statistics = settings.isIgnoreColumnStatistics();
        Set<String> paired = pairedColumns(oldState, newState);
        Set<String> collations = unmigratableCollations(oldState, newState, paired);
        if (!cache && !statistics && collations.isEmpty()) {
            return NONE;
        }

        return new IgnoredValues(
                cache && oldState instanceof ISequence && newState instanceof ISequence,
                cache ? pairedIdentities(oldState, newState, paired) : Set.of(),
                statistics ? paired : Set.of(),
                collations);
    }

    /**
     * Reports whether the comparison passes over nothing about this object, so
     * that a caller may skip the reading of its rendering entirely.
     *
     * @return true when there is nothing to mark
     */
    public boolean isEmpty() {
        return !sequenceCache && cacheColumns.isEmpty() && statisticsColumns.isEmpty()
                && collationColumns.isEmpty();
    }

    /**
     * The columns both states of an object hold, in the order the state a
     * migration starts from holds them.
     * <p>
     * A column is matched by name, exactly as the comparison matches one. A
     * table shown whole is the case this is for: it renders a statement per
     * column that has a statistics target or an identity, and only some of those
     * columns are on both sides of the screen.
     */
    private static Set<String> pairedColumns(IStatement oldState, IStatement newState) {
        if (oldState instanceof IColumn oldColumn && newState instanceof IColumn newColumn) {
            return oldColumn.getName().equals(newColumn.getName()) ? Set.of(oldColumn.getName()) : Set.of();
        }
        if (!(oldState instanceof ITable oldTable) || !(newState instanceof ITable newTable)) {
            // a container shown with its children whole - a schema, say - is not
            // read for the objects inside it: this answers about one object, and
            // under-marking is the only mistake it is allowed to make
            return Set.of();
        }

        Set<String> inNewState = new LinkedHashSet<>();
        for (IColumn column : newTable.getColumns()) {
            inNewState.add(column.getName());
        }

        Set<String> both = new LinkedHashSet<>();
        for (IColumn column : oldTable.getColumns()) {
            if (inNewState.contains(column.getName())) {
                both.add(column.getName());
            }
        }
        return both;
    }

    /**
     * The paired columns whose whole difference is a collation the migration
     * cannot express.
     * <p>
     * The migration runs from the old state to the new one, so the new one is
     * the side whose silence about a collation decides this and the pair is
     * handed over in that order. A column only one side holds is created or
     * dropped with the collation it is declared with, which is a collation the
     * script does write, so the paired columns are the only ones asked.
     */
    private static Set<String> unmigratableCollations(IStatement source, IStatement target, Set<String> paired) {
        if (paired.isEmpty()) {
            return Set.of();
        }
        if (source instanceof IColumn sourceColumn && target instanceof IColumn targetColumn) {
            return sourceColumn.differsOnlyInUnmigratableCollation(targetColumn) ? paired : Set.of();
        }
        if (!(source instanceof ITable sourceTable) || !(target instanceof ITable targetTable)) {
            return Set.of();
        }

        Set<String> found = new LinkedHashSet<>();
        for (IColumn column : sourceTable.getColumns()) {
            IColumn inTarget = paired.contains(column.getName()) ? targetTable.getColumn(column.getName()) : null;
            if (inTarget != null && column.differsOnlyInUnmigratableCollation(inTarget)) {
                found.add(column.getName());
            }
        }
        return found;
    }

    /**
     * The paired columns that own an identity sequence in both states.
     * <p>
     * A column that gains an identity between the two states is asking for the
     * whole {@code ADD GENERATED ... AS IDENTITY} clause to be written, cache
     * included; one that loses it is asking for a {@code DROP IDENTITY} that
     * takes the cache with it. Only two identities compared against each other
     * leave the cache out, which is where the setting applies and the only place
     * the mark is true.
     */
    private static Set<String> pairedIdentities(IStatement oldState, IStatement newState, Set<String> paired) {
        if (paired.isEmpty()) {
            return Set.of();
        }
        if (oldState instanceof IColumn oldColumn) {
            return oldColumn.hasIdentitySequence() && ((IColumn) newState).hasIdentitySequence()
                    ? paired : Set.of();
        }

        Set<String> identities = new LinkedHashSet<>();
        ITable newTable = (ITable) newState;
        for (IColumn column : ((ITable) oldState).getColumns()) {
            IColumn inNewState = paired.contains(column.getName()) ? newTable.getColumn(column.getName()) : null;
            if (column.hasIdentitySequence() && inNewState != null && inNewState.hasIdentitySequence()) {
                identities.add(column.getName());
            }
        }
        return identities;
    }
}
