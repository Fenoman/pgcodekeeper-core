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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

class PgAuthorMetadataTest {

    @Test
    void disabledAuthorsSkipExtensionLookupAndLeaveSchemaUnset() throws Exception {
        var settings = new CoreSettings();
        settings.setReadAuthors(false);
        Statement statement = mock(Statement.class);
        var loader = new ExposedPgJdbcLoader(settings, statement);

        loader.queryCheckExtension();

        assertNull(loader.getExtensionSchema());
        verifyNoInteractions(statement);
    }

    @Test
    void enabledAuthorsResolveExtensionSchemaFromCatalog() throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(true, false);
        when(result.getString("extversion")).thenReturn("1.0.1");
        when(result.getBoolean("disabled")).thenReturn(false);
        when(result.getString("nspname")).thenReturn("dbo_ts");
        Statement statement = mock(Statement.class);
        when(statement.executeQuery(anyString())).thenReturn(result);
        var loader = new ExposedPgJdbcLoader(new CoreSettings(), statement);

        loader.queryCheckExtension();

        assertEquals("dbo_ts", loader.getExtensionSchema());
    }

    @Test
    void disabledAuthorsNeverReadSessionUserColumn() throws Exception {
        var settings = new CoreSettings();
        settings.setReadAuthors(false);
        var loader = new ExposedPgJdbcLoader(settings, mock(Statement.class));
        loader.queryCheckExtension();
        ResultSet result = mock(ResultSet.class);

        loader.setAuthor(new PgSchema("app"), result);

        verifyNoInteractions(result);
    }

    @Test
    void enabledAuthorsReadSessionUserColumn() throws Exception {
        ResultSet extensionResult = mock(ResultSet.class);
        when(extensionResult.next()).thenReturn(true, false);
        when(extensionResult.getString("extversion")).thenReturn("1.0.1");
        when(extensionResult.getBoolean("disabled")).thenReturn(false);
        when(extensionResult.getString("nspname")).thenReturn("dbo_ts");
        Statement statement = mock(Statement.class);
        when(statement.executeQuery(anyString())).thenReturn(extensionResult);
        var loader = new ExposedPgJdbcLoader(new CoreSettings(), statement);
        loader.queryCheckExtension();
        ResultSet result = mock(ResultSet.class);
        when(result.getString("ses_user")).thenReturn("ddl_author");

        var schema = new PgSchema("app");
        loader.setAuthor(schema, result);

        verify(result).getString("ses_user");
        assertEquals("ddl_author", schema.getAuthor());
    }

    private static final class ExposedPgJdbcLoader extends PgJdbcLoader {

        private ExposedPgJdbcLoader(ISettings settings, Statement statement) {
            super(mockConnector(), "UTC", settings);
            this.statement = statement;
        }

        private static IJdbcConnector mockConnector() {
            IJdbcConnector connector = mock(IJdbcConnector.class);
            when(connector.getDbName()).thenReturn("test");
            return connector;
        }
    }
}
