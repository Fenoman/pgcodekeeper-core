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
package org.pgcodekeeper.core.database.pg.jdbc;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.Test;

class PgJdbcConnectorPropertiesTest {

    @Test
    void connectionPropertiesAssumeSupportedMinimumServerVersion() {
        Properties props = new PgJdbcConnector("localhost", 5432, "db").makeProperties();

        assertAll(
                () -> assertEquals("9.4", props.getProperty("assumeMinServerVersion")),
                () -> assertNotNull(props.getProperty("ApplicationName")),
                () -> assertTrue(props.getProperty("ApplicationName").startsWith("pgCodeKeeper")));
    }
}
