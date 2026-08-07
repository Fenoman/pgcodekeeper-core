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
package org.pgcodekeeper.core.exception;

import java.io.Serial;

import org.antlr.v4.runtime.Token;

/**
 * Thrown when a statement resolves to a schema the caller excluded from the
 * project load through
 * {@link org.pgcodekeeper.core.settings.ISettings#isAdditionalSchemaExcluded(String)}.
 * <p>
 * The statement is dropped exactly like an unresolved reference, but the drop
 * is intentional and must not be reported as a project error. A flat project
 * layout encodes the schema in the file name, and {@code a.b.c.sql} cannot be
 * split into schema and object parts by name alone, so the loader keeps such a
 * file and lets the parser resolve the real schema name.
 */
public class ExcludedSchemaException extends UnresolvedReferenceException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient String schemaName;

    /**
     * Constructs exception for an explicitly excluded schema.
     *
     * @param schemaName exact schema name resolved by the parser
     * @param errorToken the token where the excluded schema was referenced
     */
    public ExcludedSchemaException(String schemaName, Token errorToken) {
        super("Schema excluded by the caller: " + schemaName, errorToken);
        this.schemaName = schemaName;
    }

    /**
     * Gets the excluded schema name.
     *
     * @return exact schema name resolved by the parser
     */
    public String getSchemaName() {
        return schemaName;
    }
}
