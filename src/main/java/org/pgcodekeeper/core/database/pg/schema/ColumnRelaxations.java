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
package org.pgcodekeeper.core.database.pg.schema;

import org.pgcodekeeper.core.settings.ISettings;

/**
 * The differences between two states of a column that a comparison is asked to
 * overlook, each because the generator provably writes nothing for it.
 * <p>
 * Every one of them is a relaxation of the comparison alone, never of
 * {@code equals} or of the hash: the two states stay unequal and keep hashing
 * apart, so a caller that has not asked for the relaxation is unaffected. A
 * relaxation reaching only one of comparison and hash would leave a pair that
 * one of them calls equal while the other tells it apart, which is exactly the
 * failure {@code Comparison} guards against everywhere else.
 * <p>
 * Only ever consulted after the plain comparison has already said no, so an
 * empty set of relaxations costs nothing and decides nothing.
 *
 * @param unmigratableCollation the collation the state a migration produces
 *                              does not name, see
 *                              {@link PgColumn#compareIgnoring}
 * @param sequenceCache         the cache of the identity sequence of a column,
 *                              see {@link ISettings#isIgnoreSequenceCache()}
 * @param columnStatistics      the statistics target of a column, see
 *                              {@link ISettings#isIgnoreColumnStatistics()}
 */
record ColumnRelaxations(boolean unmigratableCollation, boolean sequenceCache, boolean columnStatistics) {

    private static final ColumnRelaxations NONE = new ColumnRelaxations(false, false, false);

    private static final ColumnRelaxations COLLATION_ONLY = new ColumnRelaxations(true, false, false);

    /**
     * Nothing is overlooked: the answer for a caller comparing two states of a
     * column without a migration between them in mind.
     *
     * @return the empty set of relaxations
     */
    static ColumnRelaxations none() {
        return NONE;
    }

    /**
     * Only the collation no script can express: the relaxation that is never
     * asked for and always applies.
     * <p>
     * Asked by whoever wants to know whether that collation is the whole of the
     * difference between two states of a column rather than one part of a
     * difference the migration does carry, see
     * {@link PgColumn#differsOnlyInUnmigratableCollation}. Kept apart from
     * {@link #forMigrationTarget} on purpose: the two settings there answer for
     * differences a script expresses perfectly well, and folding them in would
     * make the answer depend on preferences that have nothing to do with what a
     * script can write.
     *
     * @return the relaxation that drops an unmigratable collation and nothing
     * else
     */
    static ColumnRelaxations collationOnly() {
        return COLLATION_ONLY;
    }

    /**
     * The relaxations of a comparison whose answer decides whether a migration
     * script is built, with the state that migration produces on the other
     * side.
     * <p>
     * The unmigratable collation is always overlooked, because no script can
     * express it. The cache of an identity sequence and the statistics target
     * are overlooked only on request, because a script can express either
     * perfectly well and it is the operator who declares the difference
     * uninteresting.
     *
     * @param settings settings of the comparison
     * @return the relaxations those settings ask for
     */
    static ColumnRelaxations forMigrationTarget(ISettings settings) {
        return new ColumnRelaxations(true, settings.isIgnoreSequenceCache(),
                settings.isIgnoreColumnStatistics());
    }

    /**
     * Reports whether anything at all is overlooked, so that a caller may skip
     * the second, relaxed pass over the columns entirely.
     *
     * @return true when at least one difference is overlooked
     */
    boolean any() {
        return unmigratableCollation || sequenceCache || columnStatistics;
    }
}
