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
package org.pgcodekeeper.core.database.pg.loader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.api.loader.ComparisonSide;
import org.pgcodekeeper.core.database.pg.routine.ProjectRoutineBodyCatalogChannel;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * Verifies the old-side role signal of the JDBC loader: the role exists only
 * while the comparison binding attaches the project catalog with an OLD JDBC
 * side, so standalone loads and NEW-side loads never enable the old-side
 * routine body skip.
 */
class PgJdbcLoaderComparisonSideTest {

    @Test
    void oldSideRoleExistsOnlyWhileAttachedAsOldSide() {
        var loader = new PgJdbcLoader(
                mock(IJdbcConnector.class), Consts.UTC, new CoreSettings());
        assertFalse(loader.isComparisonOldSide(),
                "standalone loads must never report the old comparison side");

        var channel = new ProjectRoutineBodyCatalogChannel();
        channel.open();
        loader.attachProjectRoutineBodyCatalog(channel, ComparisonSide.OLD);
        assertTrue(loader.isComparisonOldSide());

        loader.detachProjectRoutineBodyCatalog(channel);
        assertFalse(loader.isComparisonOldSide(),
                "detaching the binding must clear the role");
        channel.close();
    }

    @Test
    void newSideAttachmentNeverReportsOldSide() {
        var loader = new PgJdbcLoader(
                mock(IJdbcConnector.class), Consts.UTC, new CoreSettings());
        var channel = new ProjectRoutineBodyCatalogChannel();
        channel.open();
        loader.attachProjectRoutineBodyCatalog(channel, ComparisonSide.NEW);
        assertFalse(loader.isComparisonOldSide(),
                "a NEW-side database emits bodies into the script and must analyze them");
        loader.detachProjectRoutineBodyCatalog(channel);
        channel.close();
    }
}
