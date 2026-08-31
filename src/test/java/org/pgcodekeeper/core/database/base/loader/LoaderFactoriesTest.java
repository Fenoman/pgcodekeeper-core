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

import java.util.Collection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.loader.ILoaderFactory;
import org.pgcodekeeper.core.database.api.loader.IProjectLoader;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.library.LibraryXmlStore;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

class LoaderFactoriesTest {

    @Test
    void factoryContractsRejectNullInputs(@TempDir Path project) throws Exception {
        ILoaderFactory factory = LoaderFactories.of(TestLoader::new);

        assertThrows(NullPointerException.class, () -> LoaderFactories.of(null));
        assertThrows(NullPointerException.class, () -> LoaderFactories.project(null, TestProjectLoader::new));
        assertThrows(NullPointerException.class, () -> LoaderFactories.project(project, null));
        assertThrows(NullPointerException.class, () -> factory.create(null));
        assertThrows(NullPointerException.class,
                () -> LoaderFactories.project(project, TestProjectLoader::new)
                        .contributeCommonConfiguration(null));
        assertThrows(NullPointerException.class,
                () -> LoaderFactories.project(project, TestProjectLoader::new).create(null));
        assertThrows(NullPointerException.class,
                () -> LoaderFactories.of(settings -> null).create(new CoreSettings()));
        assertThrows(NullPointerException.class,
                () -> LoaderFactories.project(project, settings -> null).create(new CoreSettings()));
        assertThrows(NullPointerException.class, () -> new ComparisonLoaderFactories(null, factory));
        assertThrows(NullPointerException.class, () -> new ComparisonLoaderFactories(factory, null));
    }

    @Test
    void ordinaryFactoryRejectsForeignSettingsAndClosesProduct() {
        var supplied = new CoreSettings();
        var product = new TestLoader(new CoreSettings());
        ILoaderFactory factory = LoaderFactories.of(settings -> product);

        assertThrows(IllegalArgumentException.class, () -> factory.create(supplied));

        assertEquals(1, product.getCloseCount());
    }

    @Test
    void projectFactoryRejectsWrongTypeAndForeignSettingsBeforeAcknowledgment(@TempDir Path project) {
        var supplied = new CoreSettings();
        var wrongType = new TestLoader(supplied);
        var wrongTypeFactory = LoaderFactories.project(project, settings -> wrongType);

        assertThrows(IllegalArgumentException.class, () -> wrongTypeFactory.create(supplied));
        assertEquals(1, wrongType.getCloseCount());

        var foreignProject = new TestProjectLoader(new CoreSettings());
        var foreignSettingsFactory = LoaderFactories.project(project, settings -> foreignProject);

        assertThrows(IllegalArgumentException.class, () -> foreignSettingsFactory.create(supplied));
        assertEquals(0, foreignProject.acknowledgmentCount);
        assertEquals(1, foreignProject.getCloseCount());
    }

    @Test
    void projectFactoryClosesConstructedLoaderWhenDefaultAcknowledgmentFails(@TempDir Path project) {
        var settings = new CoreSettings();
        var product = new DefaultAcknowledgmentProjectLoader(settings, null);
        var factory = LoaderFactories.project(project, supplied -> product);

        assertThrows(UnsupportedOperationException.class, () -> factory.create(settings));

        assertEquals(1, product.getCloseCount());
    }

    @Test
    void acknowledgmentCloseFailureIsSuppressedOnPrimaryFailure(@TempDir Path project) {
        var settings = new CoreSettings();
        var closeFailure = new IOException("close failed");
        var product = new DefaultAcknowledgmentProjectLoader(settings, closeFailure);
        var factory = LoaderFactories.project(project, supplied -> product);

        UnsupportedOperationException primary = assertThrows(
                UnsupportedOperationException.class, () -> factory.create(settings));

        assertEquals(1, product.getCloseCount());
        assertEquals(1, primary.getSuppressed().length);
        assertSame(closeFailure, primary.getSuppressed()[0]);
    }

    @Test
    void successfulFactoriesReturnProductsWithSuppliedSettings(@TempDir Path project) throws Exception {
        var ordinarySettings = new CoreSettings();
        ILoader ordinary = LoaderFactories.of(TestLoader::new).create(ordinarySettings);
        var projectSettings = new CoreSettings();
        ILoader projectLoader = LoaderFactories.project(project, TestProjectLoader::new).create(projectSettings);

        assertSame(ordinarySettings, ordinary.getSettings());
        assertSame(projectSettings, projectLoader.getSettings());
        assertEquals(1, ((TestProjectLoader) projectLoader).acknowledgmentCount);
    }

    @Test
    void projectContributionsRunOldThenNewBeforeIndependentCreation(@TempDir Path root) throws Exception {
        Path oldProject = root.resolve("old");
        Path newProject = root.resolve("new");
        writeCommonConfiguration(oldProject, "old_rule", """
                SHOW ALL
                HIDE NONE shared
                HIDE NONE old_hidden
                """, "old_source");
        writeCommonConfiguration(newProject, "new_rule", """
                HIDE ALL
                SHOW NONE shared
                SHOW NONE new_visible
                """, "new_source");

        var events = new ArrayList<String>();
        var common = new OrderedContributionSettings(events,
                Map.of(oldProject, "OLD", newProject, "NEW"));
        var oldFactory = LoaderFactories.project(oldProject, settings -> {
            events.add("OLD create");
            return new TestProjectLoader(settings);
        });
        var newFactory = LoaderFactories.project(newProject, settings -> {
            events.add("NEW create");
            return new TestProjectLoader(settings);
        });

        oldFactory.contributeCommonConfiguration(common);
        newFactory.contributeCommonConfiguration(common);
        ISettings oldSettings = common.copy();
        ISettings newSettings = common.copy();
        ILoader oldLoader = oldFactory.create(oldSettings);
        ILoader newLoader = newFactory.create(newSettings);

        assertEquals(List.of("OLD contribute", "NEW contribute", "OLD create", "NEW create"), events);
        assertNotSame(oldSettings, newSettings);
        assertSame(oldSettings, oldLoader.getSettings());
        assertSame(newSettings, newLoader.getSettings());
        assertEquals(List.of("old_rule", "new_rule"), ignoreRuleNames(common));
        assertEquals(List.of("old_rule", "new_rule"), ignoreRuleNames(oldSettings));
        assertEquals(List.of("old_rule", "new_rule"), ignoreRuleNames(newSettings));
        assertEquals(List.of("public.old_source", "public.new_source"), dependencySources(common));
        assertEquals(dependencySources(common), dependencySources(oldSettings));
        assertEquals(dependencySources(common), dependencySources(newSettings));
        assertFalse(common.isAllowedSchema("shared"), "OLD first matching rule must win");
        assertFalse(common.isAllowedSchema("old_hidden"));
        assertTrue(common.isAllowedSchema("new_visible"));
        assertFalse(common.isAllowedSchema("unmatched"), "NEW HIDE ALL remains the final default");
    }

    @Test
    void projectContributionScansOnlyExactRootAllowlist(@TempDir Path root)
            throws Exception {
        Path project = root.resolve("project");
        writeCommonConfiguration(project, "root_rule", """
                SHOW ALL
                HIDE NONE root_hidden
                """, "root_source");

        Files.writeString(project.resolve(LibraryXmlStore.FILE_NAME),
                "TABLE public.library_source -> TABLE public.library_target;\n");
        Files.writeString(project.resolve("structure.properties"), "HIDE NONE structure_rule\n");
        Files.writeString(project.resolve("objects.sql"), "HIDE NONE sql_rule\n");
        Files.writeString(project.resolve(AbstractProjectLoader.IGNORE_FILE + ".backup"),
                "SHOW ALL\nHIDE NONE backup_rule\n");
        Path overrides = project.resolve(AbstractProjectLoader.OVERRIDES_DIR);
        Files.createDirectories(overrides);
        Files.writeString(overrides.resolve(AbstractProjectLoader.IGNORE_FILE),
                "SHOW ALL\nHIDE NONE override_rule\n");
        Path nested = project.resolve("nested");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve(AbstractProjectLoader.ADDITIONAL_DEPENDENCIES_FILE),
                "TABLE public.nested_source -> TABLE public.nested_target;\n");

        var settings = new CoreSettings();
        LoaderFactories.project(project, TestProjectLoader::new)
                .contributeCommonConfiguration(settings);

        assertEquals(List.of("root_rule"), ignoreRuleNames(settings));
        assertEquals(List.of("public.root_source"), dependencySources(settings));
        assertFalse(settings.isAllowedSchema("root_hidden"));
        assertTrue(settings.isAllowedSchema("override_rule"));
    }

    @Test
    void projectContributionFollowsRegularFileSymlinks(@TempDir Path root) throws Exception {
        Path sources = root.resolve("sources");
        Path project = root.resolve("project");
        writeCommonConfiguration(sources, "linked_rule", """
                SHOW ALL
                HIDE NONE linked_hidden
                """, "linked_source");
        Files.createDirectories(project);
        try {
            Files.createSymbolicLink(project.resolve(AbstractProjectLoader.IGNORE_FILE),
                    sources.resolve(AbstractProjectLoader.IGNORE_FILE));
            Files.createSymbolicLink(project.resolve(AbstractProjectLoader.IGNORE_SCHEMA_FILE),
                    sources.resolve(AbstractProjectLoader.IGNORE_SCHEMA_FILE));
            Files.createSymbolicLink(project.resolve(AbstractProjectLoader.ADDITIONAL_DEPENDENCIES_FILE),
                    sources.resolve(AbstractProjectLoader.ADDITIONAL_DEPENDENCIES_FILE));
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            Assumptions.assumeTrue(false, () -> "Symbolic links are unavailable: " + ex);
            return;
        }

        var settings = new CoreSettings();
        LoaderFactories.project(project, TestProjectLoader::new)
                .contributeCommonConfiguration(settings);

        assertEquals(List.of("linked_rule"), ignoreRuleNames(settings));
        assertEquals(List.of("public.linked_source"), dependencySources(settings));
        assertFalse(settings.isAllowedSchema("linked_hidden"));
    }

    @Test
    void projectContributionIgnoresNonRegularAllowlistEntries(@TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve(AbstractProjectLoader.IGNORE_FILE));
        Files.createDirectories(project.resolve(AbstractProjectLoader.IGNORE_SCHEMA_FILE));
        Files.createDirectories(project.resolve(AbstractProjectLoader.ADDITIONAL_DEPENDENCIES_FILE));
        var settings = new CoreSettings();

        LoaderFactories.project(project, TestProjectLoader::new)
                .contributeCommonConfiguration(settings);

        assertTrue(ignoreRuleNames(settings).isEmpty());
        assertTrue(dependencySources(settings).isEmpty());
        assertTrue(settings.isAllowedSchema("anything"));
    }

    @Test
    void projectContributionPropagatesIgnoreIoFailure(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve(AbstractProjectLoader.IGNORE_FILE), "SHOW ALL\n");
        var failure = new IOException("sentinel");
        var settings = new ThrowingIgnoreSettings(failure);

        IOException thrown = assertThrows(IOException.class,
                () -> LoaderFactories.project(project, TestProjectLoader::new)
                        .contributeCommonConfiguration(settings));

        assertSame(failure, thrown);
    }

    @Test
    void disabledProjectContributionStillAcknowledgesCreatedLoader(@TempDir Path project) throws Exception {
        writeCommonConfiguration(project, "disabled_rule", """
                SHOW ALL
                HIDE NONE disabled_schema
                """, "disabled_source");
        var settings = new CoreSettings();
        settings.setDisableAutoLoad(true);
        var factory = LoaderFactories.project(project, TestProjectLoader::new);

        factory.contributeCommonConfiguration(settings);
        TestProjectLoader loader = (TestProjectLoader) factory.create(settings);

        assertTrue(ignoreRuleNames(settings).isEmpty());
        assertTrue(dependencySources(settings).isEmpty());
        assertTrue(settings.isAllowedSchema("disabled_schema"));
        assertEquals(1, loader.acknowledgmentCount);
    }

    @Test
    void postConstructionRuntimeAndErrorCloseRejectedProduct() {
        for (Throwable primary : List.of(
                new IllegalStateException("settings failed"),
                new AssertionError("settings failed"))) {
            var settings = new CoreSettings();
            var product = new TestLoader(settings, null, primary);
            var factory = LoaderFactories.of(supplied -> product);

            Throwable thrown = assertThrows(Throwable.class, () -> factory.create(settings));

            assertSame(primary, thrown);
            assertEquals(1, product.getCloseCount());
        }
    }

    @Test
    void cleanupSuppressesRuntimeAndErrorCloseFailures(@TempDir Path project) {
        for (Throwable closeFailure : List.of(
                new IllegalStateException("close failed"),
                new AssertionError("close failed"))) {
            var settings = new CoreSettings();
            var primary = new UnsupportedOperationException("ack failed");
            var product = new FailingAcknowledgmentProjectLoader(
                    settings, closeFailure, primary);
            var factory = LoaderFactories.project(project, supplied -> product);

            Throwable thrown = assertThrows(Throwable.class, () -> factory.create(settings));

            assertSame(primary, thrown);
            assertEquals(1, product.getCloseCount());
            assertEquals(1, thrown.getSuppressed().length);
            assertSame(closeFailure, thrown.getSuppressed()[0]);
        }
    }

    @Test
    void cleanupSuppressionIsIdentitySafeAndDoesNotDuplicateExistingFailure(@TempDir Path project) {
        var settings = new CoreSettings();
        var existingCloseFailure = new IllegalStateException("close failed");
        var primary = new UnsupportedOperationException("ack failed");
        primary.addSuppressed(existingCloseFailure);
        var duplicateProduct = new FailingAcknowledgmentProjectLoader(
                settings, existingCloseFailure, primary);

        Throwable duplicateThrown = assertThrows(Throwable.class,
                () -> LoaderFactories.project(project, supplied -> duplicateProduct).create(settings));

        assertSame(primary, duplicateThrown);
        assertEquals(1, duplicateProduct.getCloseCount());
        assertEquals(1, duplicateThrown.getSuppressed().length);
        assertSame(existingCloseFailure, duplicateThrown.getSuppressed()[0]);

        var sharedFailure = new AssertionError("shared failure");
        var sharedProduct = new FailingAcknowledgmentProjectLoader(
                settings, sharedFailure, sharedFailure);

        Throwable sharedThrown = assertThrows(Throwable.class,
                () -> LoaderFactories.project(project, supplied -> sharedProduct).create(settings));

        assertSame(sharedFailure, sharedThrown);
        assertEquals(1, sharedProduct.getCloseCount());
        assertEquals(0, sharedThrown.getSuppressed().length);
    }

    private static void writeCommonConfiguration(Path project, String ignoreRule,
            String ignoreSchemaRules, String dependencySource) throws IOException {
        Files.createDirectories(project);
        Files.writeString(project.resolve(AbstractProjectLoader.IGNORE_FILE),
                "SHOW ALL\nHIDE NONE " + ignoreRule + '\n');
        Files.writeString(project.resolve(AbstractProjectLoader.IGNORE_SCHEMA_FILE), ignoreSchemaRules);
        Files.writeString(project.resolve(AbstractProjectLoader.ADDITIONAL_DEPENDENCIES_FILE),
                "TABLE public.%s -> TABLE public.%s_target;\n"
                        .formatted(dependencySource, dependencySource));
    }

    private static List<String> ignoreRuleNames(ISettings settings) {
        return settings.getIgnoreList().getList().stream().map(rule -> rule.getName()).toList();
    }

    private static List<String> dependencySources(ISettings settings) {
        return settings.getAdditionalDependencies().stream()
                .map(dependency -> dependency.source().getFullName())
                .toList();
    }

    private static class TestLoader implements ILoader {

        private final ISettings settings;
        private final Throwable closeFailure;
        private final Throwable settingsFailure;
        private int closeCount;

        private TestLoader(ISettings settings) {
            this(settings, null, null);
        }

        private TestLoader(ISettings settings, Throwable closeFailure) {
            this(settings, closeFailure, null);
        }

        private TestLoader(ISettings settings, Throwable closeFailure, Throwable settingsFailure) {
            this.settings = settings;
            this.closeFailure = closeFailure;
            this.settingsFailure = settingsFailure;
        }

        @Override
        public IDatabase load() {
            return null;
        }

        @Override
        public IDatabase loadAndAnalyze() {
            return null;
        }

        @Override
        public IDatabase getDatabase() {
            return null;
        }

        @Override
        public String getDatabaseName() {
            return "test";
        }

        @Override
        public ISettings getSettings() {
            throwUnchecked(settingsFailure);
            return settings;
        }

        @Override
        public List<Object> getErrors() {
            return List.of();
        }

        final int getCloseCount() {
            return closeCount;
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeFailure instanceof IOException io) {
                throw io;
            }
            throwUnchecked(closeFailure);
        }

        static void throwUnchecked(Throwable failure) {
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    private static final class TestProjectLoader extends TestLoader implements IProjectLoader {

        @Override
        public IDatabase loadFiles(Collection<Path> files) {
            throw new UnsupportedOperationException("test stub");
        }

        private int acknowledgmentCount;

        private TestProjectLoader(ISettings settings) {
            super(settings);
        }

        @Override
        public void markCommonConfigurationContributed() {
            acknowledgmentCount++;
        }
    }

    private static final class DefaultAcknowledgmentProjectLoader extends TestLoader
            implements IProjectLoader {

        @Override
        public IDatabase loadFiles(Collection<Path> files) {
            throw new UnsupportedOperationException("test stub");
        }

        private DefaultAcknowledgmentProjectLoader(ISettings settings, Throwable closeFailure) {
            super(settings, closeFailure);
        }
    }

    private static final class FailingAcknowledgmentProjectLoader extends TestLoader
            implements IProjectLoader {

        @Override
        public IDatabase loadFiles(Collection<Path> files) {
            throw new UnsupportedOperationException("test stub");
        }

        private final Throwable acknowledgmentFailure;

        private FailingAcknowledgmentProjectLoader(ISettings settings, Throwable closeFailure,
                Throwable acknowledgmentFailure) {
            super(settings, closeFailure);
            this.acknowledgmentFailure = acknowledgmentFailure;
        }

        @Override
        public void markCommonConfigurationContributed() {
            throwUnchecked(acknowledgmentFailure);
        }
    }

    private static final class OrderedContributionSettings extends CoreSettings {

        private final List<String> events;
        private final Map<Path, String> sideNames;

        private OrderedContributionSettings(List<String> events, Map<Path, String> sideNames) {
            this.events = events;
            this.sideNames = sideNames;
        }

        @Override
        public void addIgnoreList(Path ignoreListPath) throws IOException {
            events.add(sideNames.get(ignoreListPath.getParent()) + " contribute");
            super.addIgnoreList(ignoreListPath);
        }
    }

    private static final class ThrowingIgnoreSettings extends CoreSettings {

        private final IOException failure;

        private ThrowingIgnoreSettings(IOException failure) {
            this.failure = failure;
        }

        @Override
        public void addIgnoreList(Path ignoreListPath) throws IOException {
            throw failure;
        }
    }
}
