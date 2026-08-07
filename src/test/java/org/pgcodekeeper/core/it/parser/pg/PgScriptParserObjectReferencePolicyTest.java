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
package org.pgcodekeeper.core.it.parser.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.DangerStatement;
import org.pgcodekeeper.core.database.base.parser.ScriptParser;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.settings.CoreSettings;

class PgScriptParserObjectReferencePolicyTest {

    @Test
    void scriptModeForcesReferenceIndexForBatchesAndDangerClassification()
            throws Exception {
        String name = "dangerous-script.sql";
        String script = """
                CREATE TABLE public.script_guard (
                    id integer,
                    obsolete integer
                );
                ALTER TABLE public.script_guard DROP COLUMN obsolete;
                DROP TABLE public.script_guard;
                """;
        var settings = new DisabledObjectReferenceSettings();
        var loader = new PgDumpLoader(
                () -> new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),
                name, settings);

        var parser = new ScriptParser(loader, name, script);

        assertTrue(parser.batch().size() >= 3);
        assertEquals(Set.of(DangerStatement.DROP_COLUMN, DangerStatement.DROP_TABLE),
                parser.getDangerDdl(Set.of()));
        assertTrue(parser.isDangerDdl(Set.of()));
        assertNull(parser.getErrorMessage());
    }

    private static final class DisabledObjectReferenceSettings extends CoreSettings {

        @Override
        public boolean isCollectObjectReferences() {
            return false;
        }
    }
}
