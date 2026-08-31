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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;

class PgDatabaseObjectReferenceIndexTest {

    @Test
    void defaultDatabaseCollectsReferences() {
        var db = new PgDatabase();
        var location = mock(ObjectLocation.class);

        db.addReference("schema.sql", location);

        assertEquals(Set.of(location), db.getObjReferences().get("schema.sql"));
    }

    @Test
    void disabledDatabaseRejectsSingleBulkAndCopiedInsertions() {
        var location = mock(ObjectLocation.class);
        var disabled = new PgDatabase(false);
        disabled.addReference("schema.sql", location);

        var library = new PgDatabase();
        library.addReference("library.sql", location);
        disabled.addLib(library, null, null, false);

        var copy = (PgDatabase) disabled.shallowCopy();
        copy.addReference("copy.sql", location);

        assertTrue(disabled.getObjReferences().isEmpty());
        assertTrue(copy.getObjReferences().isEmpty());
    }
}
