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
package org.pgcodekeeper.core.utils;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Test-scope SLF4J binding backed only by slf4j-api. All loggers report every
 * level as disabled unless a {@link LogCapture} is active, so the test suite
 * remains silent except inside explicitly captured sections.
 */
public final class CapturingLogProvider implements SLF4JServiceProvider {

    private final ILoggerFactory loggerFactory = new CapturingLoggerFactory();
    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final MDCAdapter mdcAdapter = new NOPMDCAdapter();

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return "2.0.99";
    }

    @Override
    public void initialize() {
        // nothing to initialize
    }

    private static final class CapturingLoggerFactory implements ILoggerFactory {

        private final ConcurrentMap<String, Logger> loggers = new ConcurrentHashMap<>();

        @Override
        public Logger getLogger(String name) {
            return loggers.computeIfAbsent(name, CapturingLogger::new);
        }
    }

    private static final class CapturingLogger extends LegacyAbstractLogger {

        private static final long serialVersionUID = 1L;

        private CapturingLogger(String name) {
            this.name = name;
        }

        @Override
        public boolean isTraceEnabled() {
            return LogCapture.isAnyActive();
        }

        @Override
        public boolean isDebugEnabled() {
            return LogCapture.isAnyActive();
        }

        @Override
        public boolean isInfoEnabled() {
            return LogCapture.isAnyActive();
        }

        @Override
        public boolean isWarnEnabled() {
            return LogCapture.isAnyActive();
        }

        @Override
        public boolean isErrorEnabled() {
            return LogCapture.isAnyActive();
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return null;
        }

        @Override
        protected void handleNormalizedLoggingCall(Level level, Marker marker,
                String messagePattern, Object[] arguments, Throwable throwable) {
            LogCapture.record(level, MessageFormatter.basicArrayFormat(messagePattern, arguments));
        }
    }
}
