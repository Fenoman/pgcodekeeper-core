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
package org.pgcodekeeper.core.database.base.parser;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.antlr.v4.runtime.ParserRuleContext;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.parser.ParserListenerMode;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;

class CustomParserListenerCancellationTest {

    @Test
    void customParserListenerDoesNotConvertCancellationIntoDiagnostic() {
        var settings = new CoreSettings();
        var monitor = new NullMonitor();
        monitor.setCancelled(true);
        settings.setMonitor(monitor);
        var listener = new ExposedListener(settings);

        assertThrows(MonitorCancelledRuntimeException.class, listener::parseCancelledStatement);

        assertTrue(settings.getErrors().isEmpty(), settings.getErrors()::toString);
    }

    @Test
    void customParserListenerPropagatesMonitorFailureByIdentityWithoutDiagnostic() {
        var settings = new CoreSettings();
        var monitorFailure = new IllegalStateException("controlled monitor failure");
        settings.setMonitor(new NullMonitor() {
            @Override
            public boolean isCancelled() {
                throw monitorFailure;
            }
        });
        var listener = new ExposedListener(settings);

        RuntimeException thrown = assertThrows(
                RuntimeException.class, listener::parseCancelledStatement);

        assertSame(monitorFailure, thrown);
        assertTrue(settings.getErrors().isEmpty(), settings.getErrors()::toString);
    }

    private static final class ExposedListener extends CustomParserListener<PgDatabase> {

        private ExposedListener(CoreSettings settings) {
            super(new PgDatabase(), "test.sql", ParserListenerMode.SCRIPT, settings);
        }

        private void parseCancelledStatement() {
            safeParseStatement(() -> { }, (ParserRuleContext) null);
        }
    }
}
