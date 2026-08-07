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
package org.pgcodekeeper.core.database.api.schema;

import org.pgcodekeeper.core.hasher.IHashable;

/**
 * Interface for database sequence
 */
public interface ISequence extends IStatement, IRelation, IHashable {

    @Override
    default DbObjType getStatementType() {
        return DbObjType.SEQUENCE;
    }

    /**
     * Compares two states of this sequence while the cache of both is left out.
     * <p>
     * Asked only while {@link org.pgcodekeeper.core.settings.ISettings#isIgnoreSequenceCache()}
     * is on, and only after plain equality has already said no. The answer must
     * be exactly as strict as the question the generator asks, that is, it may
     * overlook the cache and nothing else, because an object it calls unchanged
     * is one no migration script will be built for.
     * <p>
     * A dialect that does not take part in the relaxation inherits the strict
     * answer and is thereby unaffected by the setting.
     *
     * @param target the other state of this sequence
     * @return true if the two states are equal up to their cache
     */
    default boolean compareIgnoringCache(ISequence target) {
        return equals(target);
    }

    /**
     * Hashes this sequence with its cache left out, the guard belonging to
     * {@link #compareIgnoringCache(ISequence)}.
     * <p>
     * A comparison and a hash never cover quite the same fields, so a relaxed
     * comparison needs a hash relaxed in the same one place to guard it, exactly
     * as {@code hashCode()} guards {@code equals()} everywhere else a diff is
     * decided. Inherited strict, together with the comparison above.
     *
     * @return the hash of this sequence without its cache
     */
    default int hashIgnoringCache() {
        return hashCode();
    }
}
