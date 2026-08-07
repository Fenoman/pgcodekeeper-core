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
 *
 * Copyright 2006 StartNet s.r.o.
 *
 * Distributed under MIT license
 *******************************************************************************/
package org.pgcodekeeper.core.settings;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.base.formatter.FormatConfiguration;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;

class CoreSettingsTest {

    @Test
    void copySharesImmutableProjectFileFilter(@TempDir Path tempDir) throws IOException {
        Path filterFile = tempDir.resolve("project.filter");
        Files.writeString(filterFile, "EXCLUDE PATH SCHEMA/dbo/TABLE/x.sql\n");
        ProjectFileFilter filter = ProjectFileFilter.parse(filterFile);
        var settings = new CoreSettings();
        settings.setProjectFileFilter(filter);

        ISettings copy = settings.copy();

        assertAll(
                () -> assertSame(filter, settings.getProjectFileFilter()),
                () -> assertSame(filter, copy.getProjectFileFilter()));
    }

    @Test
    void objectReferenceIndexIsEnabledByDefault() {
        assertTrue(new CoreSettings().isCollectObjectReferences());
    }

    @Test
    void comparisonLoaderFactoriesRemainOptIn() {
        var settings = new CoreSettings();
        settings.setParallelLoad(true);

        assertFalse(settings.requiresComparisonLoaderFactories());
        assertFalse(settings.copy().requiresComparisonLoaderFactories());
    }

    @Test
    void pgRoutineBodyHashFirstDefaultsCopyAndRequireFactories() {
        var settings = new CoreSettings();

        assertAll(
                () -> assertFalse(settings.isPgRoutineBodyHashFirst()),
                () -> assertEquals(256, settings.getPgRoutineBodyResidualBatchCount()),
                () -> assertEquals(32L << 20,
                        settings.getPgRoutineBodyResidualBatchBytes()),
                () -> assertFalse(settings.requiresComparisonLoaderFactories()));

        settings.setParallelLoad(true);
        assertFalse(settings.requiresComparisonLoaderFactories());

        settings.setPgRoutineBodyHashFirst(true);
        settings.setPgRoutineBodyResidualBatchCount(17);
        settings.setPgRoutineBodyResidualBatchBytes(123_456L);
        ISettings copy = settings.copy();

        assertAll(
                () -> assertTrue(copy.isPgRoutineBodyHashFirst()),
                () -> assertEquals(17, copy.getPgRoutineBodyResidualBatchCount()),
                () -> assertEquals(123_456L, copy.getPgRoutineBodyResidualBatchBytes()),
                () -> assertTrue(copy.requiresComparisonLoaderFactories()));
    }

    @Test
    void pgCatalogCacheDefaultsCopyAndValidation() {
        var settings = new CoreSettings();

        assertAll(
                () -> assertNull(settings.getPgCatalogCacheDir()),
                () -> assertEquals(512L, settings.getPgCatalogCacheMaxMb()));

        settings.setPgCatalogCacheDir("/tmp/pgck-cache");
        settings.setPgCatalogCacheMaxMb(64L);
        settings.setPgCatalogCacheRows(true);
        settings.setPgParallelCatalogReaders(3);
        ISettings copy = settings.copy();

        assertAll(
                () -> assertEquals("/tmp/pgck-cache", copy.getPgCatalogCacheDir()),
                () -> assertEquals(64L, copy.getPgCatalogCacheMaxMb()),
                () -> assertTrue(copy.isPgCatalogCacheRows()),
                () -> assertEquals(3, copy.getPgParallelCatalogReaders()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> settings.setPgCatalogCacheMaxMb(0L)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> settings.setPgCatalogCacheMaxMb(-1L)));
    }

    @Test
    void pgCatalogFingerprintAndTelemetryDefaultsCopyWithoutChangingCliContract() {
        var settings = new CoreSettings();

        assertFalse(settings.isPgCatalogCacheFingerprintProbe());
        assertSame(IComparisonTelemetry.NO_OP, settings.getComparisonTelemetry());

        IComparisonTelemetry sink = new IComparisonTelemetry() { };
        settings.setPgCatalogCacheFingerprintProbe(true);
        settings.setComparisonTelemetry(sink);
        ISettings copy = settings.copy();

        assertTrue(copy.isPgCatalogCacheFingerprintProbe());
        assertSame(sink, copy.getComparisonTelemetry());
    }

    @Test
    void pgRoutineBodyResidualLimitsMustBePositive() {
        var settings = new CoreSettings();

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> settings.setPgRoutineBodyResidualBatchCount(0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> settings.setPgRoutineBodyResidualBatchCount(-1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> settings.setPgRoutineBodyResidualBatchBytes(0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> settings.setPgRoutineBodyResidualBatchBytes(-1)));
    }

    @Test
    void pgParallelCatalogReadersDefaultStaysSequentialForCompatibility() {
        // The core default stays sequential so consumers opt into reader
        // concurrency only after confirming their integration is thread-safe.
        var settings = new CoreSettings();

        assertEquals(0, settings.getPgParallelCatalogReaders());
        assertEquals(0, settings.copy().getPgParallelCatalogReaders());
    }

    @Test
    void jdbcFetchSizeDefaultsToCompatibilityModeAndCopies() {
        var settings = new CoreSettings();

        assertEquals(0, settings.getJdbcFetchSize());

        settings.setJdbcFetchSize(512);

        assertEquals(512, settings.getJdbcFetchSize());
        assertEquals(512, settings.copy().getJdbcFetchSize());
        assertThrows(IllegalArgumentException.class, () -> settings.setJdbcFetchSize(-1));
    }

    @Test
    void versionSetterTest() {
        var settings = new CoreSettings();

        Assertions.assertNull(settings.getVersion());

        settings.setVersion(PgSupportedVersion.VERSION_16);
        Assertions.assertEquals(PgSupportedVersion.VERSION_16, settings.getVersion());

        settings.setVersion(PgSupportedVersion.GP_VERSION_7);
        Assertions.assertEquals(PgSupportedVersion.GP_VERSION_7, settings.getVersion());

        settings.setVersion(PgSupportedVersion.VERSION_14);
        Assertions.assertEquals(PgSupportedVersion.GP_VERSION_7, settings.getVersion());

        settings.resetVersion();
        Assertions.assertNull(settings.getVersion());
    }

    @Test
    void copyReturnsIndependentCoreSettings() {
        var source = new CoreSettings();
        var allowed = new ArrayList<>(List.of(DbObjType.TABLE));
        var pre = new ArrayList<>(List.of("before.sql"));
        var post = new ArrayList<>(List.of("after.sql"));
        var format = FormatConfiguration.getDefaultConfig();

        source.setAllowedTypes(allowed);
        source.setPreFilePath(pre);
        source.setPostFilePath(post);
        source.setFormatConfiguration(format);
        source.setVersion(PgSupportedVersion.VERSION_16);
        source.addError("source-error");

        CoreSettings copy = assertInstanceOf(CoreSettings.class, source.copy());

        allowed.add(DbObjType.VIEW);
        pre.add("mutated-before.sql");
        post.add("mutated-after.sql");
        format.setIndentSize(99);
        source.addError("late-error");

        assertEquals(List.of(DbObjType.TABLE), List.copyOf(copy.getAllowedTypes()));
        assertEquals(List.of("before.sql"), List.copyOf(copy.getPreFilePath()));
        assertEquals(List.of("after.sql"), List.copyOf(copy.getPostFilePath()));
        assertEquals(2, copy.getFormatConfiguration().getIndentSize());
        assertEquals(List.of("source-error"), copy.getErrors());
        assertEquals(PgSupportedVersion.VERSION_16, copy.getVersion());
    }

    @Test
    void collectionSettersDefensivelyCopyInputs() {
        var settings = new CoreSettings();
        var allowed = new ArrayList<>(List.of(DbObjType.FUNCTION));
        var pre = new ArrayList<>(List.of("pre.sql"));
        var post = new ArrayList<>(List.of("post.sql"));

        settings.setAllowedTypes(allowed);
        settings.setPreFilePath(pre);
        settings.setPostFilePath(post);
        allowed.clear();
        pre.clear();
        post.clear();

        assertEquals(List.of(DbObjType.FUNCTION), List.copyOf(settings.getAllowedTypes()));
        assertEquals(List.of("pre.sql"), List.copyOf(settings.getPreFilePath()));
        assertEquals(List.of("post.sql"), List.copyOf(settings.getPostFilePath()));
    }

    @Test
    void copyPreservesHideAllDefaults(@TempDir Path tempDir) throws IOException {
        Path schemaIgnoreList = tempDir.resolve("schemas.pgcodekeeperignore");
        Files.writeString(schemaIgnoreList, "HIDE ALL\n");
        var source = new CoreSettings();
        source.getIgnoreList().setShow(false);
        source.addIgnoreSchemaList(schemaIgnoreList);

        assertFalse(source.getIgnoreList().isShow());
        assertFalse(source.isAllowedSchema("unlisted_schema"));

        CoreSettings copy = assertInstanceOf(CoreSettings.class, source.copy());

        assertFalse(copy.getIgnoreList().isShow());
        assertFalse(copy.isAllowedSchema("unlisted_schema"));
    }

    @Test
    void copyClonesIgnoreRules() {
        var source = new CoreSettings();
        var original = new IgnoredObject("orders", null, false, false,
                false, true, EnumSet.of(DbObjType.TABLE));
        source.getIgnoreList().add(original);

        CoreSettings copy = assertInstanceOf(CoreSettings.class, source.copy());
        IgnoredObject copiedRule = copy.getIgnoreList().getList().get(0);

        original.setShow(true);
        original.setIgnoreContent(true);
        original.setObjTypes(EnumSet.of(DbObjType.VIEW));

        assertAll(
                () -> assertNotSame(original, copiedRule),
                () -> assertFalse(copiedRule.isShow()),
                () -> assertFalse(copiedRule.isIgnoreContent()),
                () -> assertEquals(EnumSet.of(DbObjType.TABLE), copiedRule.getObjTypes()));
    }

    @Test
    void copyClonesEmptyNonEnumIgnoreRuleTypeSet() {
        var source = new CoreSettings();
        Set<DbObjType> originalTypes = Set.of();
        var original = new IgnoredObject("orders", null, false, false,
                false, true, originalTypes);
        source.getIgnoreList().add(original);

        CoreSettings copy = assertInstanceOf(CoreSettings.class,
                assertDoesNotThrow(source::copy));
        IgnoredObject copiedRule = copy.getIgnoreList().getList().get(0);

        assertAll(
                () -> assertNotSame(original, copiedRule),
                () -> assertNotSame(originalTypes, copiedRule.getObjTypes()),
                () -> assertTrue(copiedRule.getObjTypes().isEmpty()));

        copiedRule.getObjTypes().add(DbObjType.TABLE);
        assertTrue(originalTypes.isEmpty());
    }
}
