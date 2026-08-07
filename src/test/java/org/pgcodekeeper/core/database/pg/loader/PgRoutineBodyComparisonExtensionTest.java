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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.loader.ComparisonSide;
import org.pgcodekeeper.core.database.api.loader.IComparisonExtensionBinding;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher.BodyType;
import org.pgcodekeeper.core.database.pg.routine.OwnedRoutineBodySource;
import org.pgcodekeeper.core.database.pg.routine.ProjectRoutineBodyCatalog;
import org.pgcodekeeper.core.database.pg.routine.ProjectRoutineBodyCatalogChannel;
import org.pgcodekeeper.core.database.pg.routine.ReusableProjectRoutineBodySnapshot;
import org.pgcodekeeper.core.database.pg.routine.RoutineBodyProfile;
import org.pgcodekeeper.core.database.pg.routine.RoutineBodyRepresentation;
import org.pgcodekeeper.core.database.pg.routine.RoutineIdentity;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgFunction;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.monitor.NullMonitor;

class PgRoutineBodyComparisonExtensionTest {

    @Test
    void projectAndJdbcBindInEitherOrientationAndPublishOnlyProjectSide()
            throws Exception {
        assertBinding(ComparisonSide.OLD);
        assertBinding(ComparisonSide.NEW);
    }

    @Test
    void equalRolesDeclineWithoutMutatingJdbcLoader() throws Exception {
        PgJdbcLoader first = mock(PgJdbcLoader.class);
        PgJdbcLoader second = mock(PgJdbcLoader.class);

        assertTrue(PgRoutineBodyComparisonExtension.KEY.bind(
                new PgRoutineBodyComparisonExtension.ProjectEndpoint(),
                new PgRoutineBodyComparisonExtension.ProjectEndpoint()).isEmpty());
        assertTrue(PgRoutineBodyComparisonExtension.KEY.bind(
                new PgRoutineBodyComparisonExtension.JdbcEndpoint(first),
                new PgRoutineBodyComparisonExtension.JdbcEndpoint(second)).isEmpty());
    }

    @Test
    void sideFailureCancelsConsumerWithoutCreatingACompetingFailure() throws Exception {
        PgJdbcLoader loader = mock(PgJdbcLoader.class);
        IComparisonExtensionBinding binding = PgRoutineBodyComparisonExtension.KEY.bind(
                new PgRoutineBodyComparisonExtension.ProjectEndpoint(),
                new PgRoutineBodyComparisonExtension.JdbcEndpoint(loader)).orElseThrow();
        binding.activate();
        ArgumentCaptor<ProjectRoutineBodyCatalogChannel> channel =
                ArgumentCaptor.forClass(ProjectRoutineBodyCatalogChannel.class);
        verify(loader).attachProjectRoutineBodyCatalog(
                channel.capture(), org.mockito.ArgumentMatchers.eq(ComparisonSide.NEW));
        RuntimeException producerFailure = new RuntimeException("controlled producer failure");

        binding.sideFailed(ComparisonSide.OLD, producerFailure);
        assertThrows(InterruptedException.class,
                () -> channel.getValue().take(new NullMonitor()));
        binding.close();
    }

    @Test
    void catalogBuildFailureRemainsTheCoordinatorFailureAndCancelsConsumer()
            throws Exception {
        PgJdbcLoader loader = mock(PgJdbcLoader.class);
        IComparisonExtensionBinding binding = PgRoutineBodyComparisonExtension.KEY.bind(
                new PgRoutineBodyComparisonExtension.ProjectEndpoint(),
                new PgRoutineBodyComparisonExtension.JdbcEndpoint(loader)).orElseThrow();
        binding.activate();
        ArgumentCaptor<ProjectRoutineBodyCatalogChannel> channel =
                ArgumentCaptor.forClass(ProjectRoutineBodyCatalogChannel.class);
        verify(loader).attachProjectRoutineBodyCatalog(
                channel.capture(), org.mockito.ArgumentMatchers.eq(ComparisonSide.NEW));
        RuntimeException producerFailure = new RuntimeException("catalog build failed");
        PgDatabase database = mock(PgDatabase.class);
        when(database.getAnalysisLaunchers()).thenThrow(producerFailure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> binding.sideLoaded(ComparisonSide.OLD, database));
        binding.sideFailed(ComparisonSide.OLD, thrown);

        assertSame(producerFailure, thrown);
        assertThrows(InterruptedException.class,
                () -> channel.getValue().take(new NullMonitor()));
        binding.close();
    }

    @Test
    void reusableEndpointPublishesFreshCatalogAfterProjectLaunchersWereReleased()
            throws Exception {
        PgDatabase database = new PgDatabase();
        var schema = new PgSchema("public");
        database.addChild(schema);
        var function = new PgFunction("answer");
        schema.addChild(function);
        String canonical = new String("$$SELECT 42$$");
        function.setBody(canonical);
        var source = OwnedRoutineBodySource.exchangeCandidate(
                "SELECT 42", canonical, RoutineBodyProfile.current(false),
                RoutineBodyRepresentation.SQL_TEXT);
        database.addAnalysisLauncher(new PgFuncProcAnalysisLauncher(
                function, source, BodyType.SQL, "body", "answer.sql",
                java.util.List.of(), false));
        ReusableProjectRoutineBodySnapshot snapshot =
                ReusableProjectRoutineBodySnapshot.capture(database);
        database.clearAnalysisLaunchers();
        RoutineIdentity identity = new RoutineIdentity(
                "public", DbObjType.FUNCTION, "answer()");

        ProjectRoutineBodyCatalog first =
                takeReusableCatalog(database, snapshot);
        ProjectRoutineBodyCatalog second =
                takeReusableCatalog(database, snapshot);

        assertNotNull(first.removeCandidate(identity));
        assertNotNull(second.removeCandidate(identity));
    }

    private static void assertBinding(ComparisonSide projectSide) throws Exception {
        PgJdbcLoader loader = mock(PgJdbcLoader.class);
        var project = new PgRoutineBodyComparisonExtension.ProjectEndpoint();
        var jdbc = new PgRoutineBodyComparisonExtension.JdbcEndpoint(loader);
        IComparisonExtensionBinding binding = (projectSide == ComparisonSide.OLD
                ? PgRoutineBodyComparisonExtension.KEY.bind(project, jdbc)
                : PgRoutineBodyComparisonExtension.KEY.bind(jdbc, project)).orElseThrow();

        binding.activate();
        ArgumentCaptor<ProjectRoutineBodyCatalogChannel> channel =
                ArgumentCaptor.forClass(ProjectRoutineBodyCatalogChannel.class);
        // the JDBC loader must learn the side opposite to the project
        ComparisonSide expectedJdbcSide = projectSide == ComparisonSide.OLD
                ? ComparisonSide.NEW
                : ComparisonSide.OLD;
        verify(loader).attachProjectRoutineBodyCatalog(
                channel.capture(), org.mockito.ArgumentMatchers.eq(expectedJdbcSide));
        binding.sideLoaded(projectSide, new PgDatabase());
        ProjectRoutineBodyCatalog catalog = channel.getValue().take(new NullMonitor());

        assertNotNull(catalog);
        binding.close();
        verify(loader).detachProjectRoutineBodyCatalog(channel.getValue());
    }

    private static ProjectRoutineBodyCatalog takeReusableCatalog(
            PgDatabase database, ReusableProjectRoutineBodySnapshot snapshot)
            throws Exception {
        PgJdbcLoader loader = mock(PgJdbcLoader.class);
        IComparisonExtensionBinding binding =
                PgRoutineBodyComparisonExtension.KEY.bind(
                        new PgRoutineBodyComparisonExtension.ProjectEndpoint(snapshot),
                        new PgRoutineBodyComparisonExtension.JdbcEndpoint(loader))
                .orElseThrow();
        binding.activate();
        ArgumentCaptor<ProjectRoutineBodyCatalogChannel> channel =
                ArgumentCaptor.forClass(ProjectRoutineBodyCatalogChannel.class);
        verify(loader).attachProjectRoutineBodyCatalog(
                channel.capture(), org.mockito.ArgumentMatchers.eq(ComparisonSide.NEW));
        binding.sideLoaded(ComparisonSide.OLD, database);
        ProjectRoutineBodyCatalog catalog =
                channel.getValue().take(new NullMonitor());
        binding.close();
        return catalog;
    }
}
