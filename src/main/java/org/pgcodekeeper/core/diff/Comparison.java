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
package org.pgcodekeeper.core.diff;

import org.pgcodekeeper.core.database.api.schema.ISequence;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.database.base.schema.AbstractStatement;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Class with common logic for comparing objects.
 */
public class Comparison {

    /**
     * Compares old and new states of an object
     * <p>
     * The first question is plain equality, guarded by the hash: the two do not
     * always cover the same fields, and a pair that only the hash tells apart
     * must not be called equal. A table that fails it is asked once more without
     * the column differences a migration between the two states provably cannot
     * express, see {@link ITable#compareIgnoringUnmigratableColumns}. That
     * second question is about the state of the table itself, so its children
     * are compared separately - and under the very same hash guard, which is why
     * {@link AbstractStatement#hashChildren()} exists: {@code PgConstraintFk}
     * leaves {@code DEFERRABLE} out of its comparison while its hash keeps it,
     * and a table whose foreign key differs in nothing else must stay in the
     * diff, since {@code ALTER CONSTRAINT} does migrate that difference.
     * <p>
     * A sequence that fails plain equality while
     * {@link ISettings#isIgnoreSequenceCache()} is on is asked once more without
     * its cache, the very thing that setting also keeps out of the
     * {@code ALTER}. The identity sequence of a column is not asked here: it is
     * a part of the state of its table and is reached through the table branch
     * above. Both questions keep the hash guard, relaxed in the same one place
     * as the comparison, see {@link ISequence#hashIgnoringCache()}.
     *
     * @param settings settings for comparing objects
     * @param oldObject old object state
     * @param newObject new object state
     * @return true if objects are equals
     */
    public static boolean compare(ISettings settings, IStatement oldObject, IStatement newObject) {
        if (oldObject.hashCode() == newObject.hashCode() && oldObject.equals(newObject)) {
            return true;
        }

        if (oldObject instanceof ITable oldTable) {
            var oldStatement = (AbstractStatement) oldTable;
            var newStatement = (AbstractStatement) newObject;
            return compareTables(settings, oldTable, (ITable) newObject)
                    && oldStatement.compareChildren(newStatement)
                    && oldStatement.hashChildren() == newStatement.hashChildren();
        }

        if (settings.isIgnoreSequenceCache() && oldObject instanceof ISequence oldSequence
                && newObject instanceof ISequence newSequence) {
            return oldSequence.compareIgnoringCache(newSequence)
                    && oldSequence.hashIgnoringCache() == newSequence.hashIgnoringCache();
        }

        return false;
    }

    /**
     * Compares two states of a table ignoring the column differences the
     * migration between them provably cannot express.
     * <p>
     * The migration always ends at its target, which is the new side unless the
     * caller says otherwise, so the target is what decides the direction here
     * and the two states are handed over in that order rather than in the order
     * of the comparison.
     */
    private static boolean compareTables(ISettings settings, ITable oldTable, ITable newTable) {
        return settings.isMigrationTargetOldSide()
                ? newTable.compareIgnoringUnmigratableColumns(oldTable, settings)
                : oldTable.compareIgnoringUnmigratableColumns(newTable, settings);
    }

    /**
     * Reports whether two states of an object carry their whole difference in
     * their children, so that the object itself produces nothing in a migration
     * script.
     * <p>
     * Only meaningful for a pair that
     * {@link #compare(ISettings, IStatement, IStatement)} has already rejected:
     * the answer names the reason for that rejection. Each branch below asks
     * exactly what the matching branch of {@code compare} asked, so an object is
     * never called unchanged here on grounds weaker than the ones that would
     * have kept it out of the diff in the first place.
     * <p>
     * For a table compared while column order is ignored that is
     * {@link #compareTables}, the sole own-state test {@code compare} applies
     * there. Everywhere else {@code compare} rests on {@code equals} guarded by
     * {@code hashCode}, so the shallow comparison alone is not enough: the two
     * do not always cover the same fields - {@code PgConstraintFk} leaves
     * {@code DEFERRABLE} out of its comparison while the hash keeps it - and a
     * pair that only the hash tells apart must not be called unchanged.
     * {@link AbstractStatement#hashIgnoringChildren()} closes that gap; taken
     * together the two conditions leave the children as the only possible source
     * of the difference.
     * <p>
     * A table compared with its column order kept takes that second path, so
     * the collation {@code compare} may ignore is not ignored here. The answer
     * is then only stricter than the one that kept the object in the diff, which
     * is the safe direction: such a table stays in the tree instead of being
     * dropped with its hidden children. It carries no change of its own either
     * way.
     *
     * @param settings  settings for comparing objects
     * @param oldObject old object state
     * @param newObject new object state
     * @return true if the objects themselves are equal and only their children differ
     */
    public static boolean differsInChildrenOnly(ISettings settings, IStatement oldObject, IStatement newObject) {
        if (oldObject instanceof ITable oldTable && settings.isIgnoreColumnOrder()) {
            return compareTables(settings, oldTable, (ITable) newObject);
        }

        var oldStatement = (AbstractStatement) oldObject;
        var newStatement = (AbstractStatement) newObject;
        return oldStatement.compare(newStatement)
                && oldStatement.hashIgnoringChildren() == newStatement.hashIgnoringChildren();
    }

    private Comparison() {
        // only statics
    }
}
