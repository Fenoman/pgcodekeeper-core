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
package org.pgcodekeeper.core.database.base.loader;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.base.parser.ParserExecutionPolicy;
import org.pgcodekeeper.core.database.base.parser.AntlrTask;
import org.pgcodekeeper.core.database.base.parser.AntlrTaskManager;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.settings.ProjectFileFilter;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;

class LibSettingsTest {

    @Test
    void nestedLibrarySettingsAlwaysAllowProjectFiles(@TempDir Path tempDir) throws Exception {
        Path filterFile = tempDir.resolve("project.filter");
        Files.writeString(filterFile, "EXCLUDE REGEX .*\n");
        var parent = new CoreSettings();
        parent.setProjectFileFilter(ProjectFileFilter.parse(filterFile));

        var librarySettings = new LibSettings(parent, false);
        var nestedLibrarySettings = new LibSettings(librarySettings, false);
        var libraryCopy = librarySettings.copy();
        var nestedLibraryCopy = nestedLibrarySettings.copy();

        assertEquals(LibSettings.class, LibSettings.class
                .getDeclaredMethod("getProjectFileFilter").getDeclaringClass());
        assertSame(ProjectFileFilter.ALLOW_ALL, librarySettings.getProjectFileFilter());
        assertSame(ProjectFileFilter.ALLOW_ALL,
                nestedLibrarySettings.getProjectFileFilter());
        assertSame(ProjectFileFilter.ALLOW_ALL, libraryCopy.getProjectFileFilter());
        assertSame(ProjectFileFilter.ALLOW_ALL, nestedLibraryCopy.getProjectFileFilter());
        assertTrue(nestedLibrarySettings.getProjectFileFilter()
                .isAllowed("SCHEMA/public/TABLE/anything.sql"));
    }

    @Test
    void copySupportsExternalSettingsWithDefaultProjectFilter() {
        var ignorePrivileges = new AtomicReference<Boolean>();
        ISettings externalCopy = externalSettingsCopy(null, ignorePrivileges);
        ISettings externalParent = externalParentReturning(externalCopy);

        ISettings result = new LibSettings(externalParent, true).copy();

        assertAll(
                () -> assertSame(externalCopy, result),
                () -> assertSame(ProjectFileFilter.ALLOW_ALL,
                        result.getProjectFileFilter()),
                () -> assertEquals(Boolean.TRUE, ignorePrivileges.get()));
    }

    @Test
    void copyMasksExternalCustomFilterIncludingNestedCopies(@TempDir Path tempDir)
            throws Exception {
        Path filterFile = tempDir.resolve("external.filter");
        Files.writeString(filterFile, "EXCLUDE REGEX .*\n");
        ProjectFileFilter activeFilter = ProjectFileFilter.parse(filterFile);
        var ignorePrivileges = new AtomicReference<Boolean>();
        ISettings externalCopy = externalSettingsCopy(activeFilter, ignorePrivileges);
        ISettings externalParent = externalParentReturning(externalCopy);
        var librarySettings = new LibSettings(externalParent, false);
        var nestedLibrarySettings = new LibSettings(librarySettings, true);

        ISettings libraryCopy = librarySettings.copy();
        assertEquals(Boolean.FALSE, ignorePrivileges.get());
        ISettings nestedCopy = nestedLibrarySettings.copy();

        assertAll(
                () -> assertSame(ProjectFileFilter.ALLOW_ALL,
                        libraryCopy.getProjectFileFilter()),
                () -> assertSame(ProjectFileFilter.ALLOW_ALL,
                        nestedCopy.getProjectFileFilter()),
                () -> assertFalse(libraryCopy.isIgnorePrivileges()),
                () -> assertTrue(nestedCopy.isIgnorePrivileges()),
                () -> assertEquals(Boolean.TRUE, ignorePrivileges.get()));
    }

    @Test
    void delegatesObjectReferencePolicyToParent() {
        var settings = new LibSettings(new CopyingReferenceSettings(false), false);

        assertFalse(settings.isCollectObjectReferences());
    }

    @Test
    void copyPreservesParentObjectReferencePolicy() {
        var settings = new LibSettings(new CopyingReferenceSettings(false), false);

        assertFalse(settings.copy().isCollectObjectReferences());
    }

    @Test
    void delegatesJdbcFetchSizeToParent() {
        var parent = new CoreSettings();
        parent.setJdbcFetchSize(512);

        var settings = new LibSettings(parent, false);

        assertEquals(512, settings.getJdbcFetchSize());
    }

    @Test
    void delegatesParserExecutionPolicyToParent() {
        var parent = new CoreSettings();
        ParserExecutionPolicy policy = ParserExecutionPolicy.dedicated(3);
        parent.setParserExecutionPolicy(policy);

        var settings = new LibSettings(parent, false);

        assertSame(policy, settings.getParserExecutionPolicy());
    }

    @Test
    void delegatesRootParserQueueToNestedLibrarySettings() {
        Queue<AntlrTask<?>> rootTasks = AntlrTaskManager.createTaskQueue(
                ParserExecutionPolicy.dedicated(1));
        try {
            var settings = new LibSettings(new CoreSettings(), false, rootTasks);
            var nestedSettings = new LibSettings(settings, true);

            assertSame(rootTasks, settings.getParserTaskQueue());
            assertSame(rootTasks, nestedSettings.getParserTaskQueue());
        } finally {
            AntlrTaskManager.close(rootTasks);
        }
    }

    @Test
    void copyPreservesRootParserQueueForNewChildLoader() throws Exception {
        Queue<AntlrTask<?>> rootTasks = AntlrTaskManager.createTaskQueue(
                ParserExecutionPolicy.dedicated(1));
        try {
            ISettings copy = new LibSettings(
                    new CoreSettings(), false, rootTasks).copy();

            var child = new QueueExposingLoader(copy);
            try {
                assertSame(rootTasks, child.parserTasks());
            } finally {
                child.close();
            }
        } finally {
            AntlrTaskManager.close(rootTasks);
        }
    }

    @Test
    void delegatesAndCopiesPgRoutineBodyHashFirstSettings() {
        var parent = new CoreSettings();
        parent.setPgRoutineBodyHashFirst(true);
        parent.setPgRoutineBodyResidualBatchCount(17);
        parent.setPgRoutineBodyResidualBatchBytes(123_456L);

        var settings = new LibSettings(parent, false);

        assertTrue(settings.isPgRoutineBodyHashFirst());
        assertEquals(17, settings.getPgRoutineBodyResidualBatchCount());
        assertEquals(123_456L, settings.getPgRoutineBodyResidualBatchBytes());
        assertTrue(settings.requiresComparisonLoaderFactories());

        var copy = settings.copy();
        assertTrue(copy.isPgRoutineBodyHashFirst());
        assertEquals(17, copy.getPgRoutineBodyResidualBatchCount());
        assertEquals(123_456L, copy.getPgRoutineBodyResidualBatchBytes());
        assertTrue(copy.requiresComparisonLoaderFactories());
    }

    @Test
    void delegatesAndCopiesPgCatalogCacheSettings() {
        var parent = new CoreSettings();
        parent.setPgCatalogCacheDir("/tmp/pgck-cache");
        parent.setPgCatalogCacheMaxMb(64L);
        parent.setPgCatalogCacheFingerprintProbe(true);
        IComparisonTelemetry sink = new IComparisonTelemetry() { };
        parent.setComparisonTelemetry(sink);

        var settings = new LibSettings(parent, false);

        assertEquals("/tmp/pgck-cache", settings.getPgCatalogCacheDir());
        assertEquals(64L, settings.getPgCatalogCacheMaxMb());
        assertTrue(settings.isPgCatalogCacheFingerprintProbe());
        assertSame(sink, settings.getComparisonTelemetry());

        var copy = settings.copy();
        assertEquals("/tmp/pgck-cache", copy.getPgCatalogCacheDir());
        assertEquals(64L, copy.getPgCatalogCacheMaxMb());
        assertTrue(copy.isPgCatalogCacheFingerprintProbe());
        assertSame(sink, copy.getComparisonTelemetry());
    }

    private static ISettings externalParentReturning(ISettings copy) {
        return proxy((proxy, method, args) -> {
            if ("copy".equals(method.getName())) {
                return copy;
            }
            throw new UnsupportedOperationException(method.toString());
        });
    }

    private static ISettings externalSettingsCopy(ProjectFileFilter customFilter,
            AtomicReference<Boolean> ignorePrivileges) {
        return proxy((proxy, method, args) -> {
            return switch (method.getName()) {
                case "copy" -> proxy;
                case "getProjectFileFilter" -> customFilter == null
                        ? InvocationHandler.invokeDefault(proxy, method)
                        : customFilter;
                case "isIgnorePrivileges" -> Boolean.TRUE.equals(ignorePrivileges.get());
                case "setIgnorePrivileges" -> {
                    ignorePrivileges.set((Boolean) args[0]);
                    yield null;
                }
                default -> {
                    if (method.isDefault()) {
                        yield InvocationHandler.invokeDefault(proxy, method,
                                args == null ? new Object[0] : args);
                    }
                    throw new UnsupportedOperationException(method.toString());
                }
            };
        });
    }

    private static ISettings proxy(InvocationHandler handler) {
        return (ISettings) Proxy.newProxyInstance(ISettings.class.getClassLoader(),
                new Class<?>[] { ISettings.class }, handler);
    }

    private static final class QueueExposingLoader extends AbstractLoader<IDatabase> {

        private QueueExposingLoader(ISettings settings) {
            super(settings, "copy-child");
        }

        @Override
        protected IDatabase loadInternal() {
            return null;
        }

        @Override
        protected IDatabase createDatabase() {
            return null;
        }

        private Queue<AntlrTask<?>> parserTasks() {
            return antlrTasks;
        }
    }

    private static final class CopyingReferenceSettings extends CoreSettings {

        private final boolean collectObjectReferences;

        private CopyingReferenceSettings(boolean collectObjectReferences) {
            this.collectObjectReferences = collectObjectReferences;
        }

        @Override
        public boolean isCollectObjectReferences() {
            return collectObjectReferences;
        }

        @Override
        public CoreSettings shallowCopy() {
            return new CopyingReferenceSettings(collectObjectReferences);
        }
    }
}
