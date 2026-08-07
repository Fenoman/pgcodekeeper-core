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
package org.pgcodekeeper.core.script;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.settings.CoreSettings;

class SQLScriptForcedPhaseTest {

    @Test
    void addAllStatementsCanForceSourcePhasesIntoEndWithoutChangingTheirOrder() {
        var settings = new CoreSettings();
        var source = new SQLScript(settings, ";");
        source.addStatement("source pre", SQLActionType.PRE);
        source.addStatement("source mid", SQLActionType.MID);

        var target = new SQLScript(settings, ";");
        target.addStatement("target mid", SQLActionType.MID);
        target.addStatement("target end", SQLActionType.END);

        target.addAllStatements(source, SQLActionType.END);

        assertEquals("""
                target mid;

                target end;

                source pre;

                source mid;""", target.getFullScript());
    }
}
