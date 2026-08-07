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

import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.api.loader.ComparisonExtensionContext;
import org.pgcodekeeper.core.database.api.loader.ComparisonSide;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.base.jdbc.ICatalogRowCache;
import org.pgcodekeeper.core.database.base.jdbc.QueryBuilder;
import org.pgcodekeeper.core.database.base.loader.AbstractJdbcLoader;
import org.pgcodekeeper.core.database.base.parser.AntlrTaskManager;
import org.pgcodekeeper.core.database.base.schema.AbstractStatement;
import org.pgcodekeeper.core.database.pg.jdbc.*;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.routine.PgJdbcRoutineBodyCatalogMode;
import org.pgcodekeeper.core.database.pg.routine.PgJdbcRoutineBodyResolution;
import org.pgcodekeeper.core.database.pg.routine.PgRoutineBodyBatchLimits;
import org.pgcodekeeper.core.database.pg.routine.PgRoutineBodyCache;
import org.pgcodekeeper.core.database.pg.routine.PgRoutineBodyDivergencePolicy;
import org.pgcodekeeper.core.database.pg.routine.ProjectRoutineBodyCatalogChannel;
import org.pgcodekeeper.core.database.pg.routine.PgRoutineBodyResidualTransport;
import org.pgcodekeeper.core.database.pg.routine.RoutineBodyProfile;
import org.pgcodekeeper.core.database.pg.routine.RoutineBodyRepresentation;
import org.pgcodekeeper.core.database.pg.routine.RoutineBodySource;
import org.pgcodekeeper.core.database.pg.routine.RoutineFingerprint;
import org.pgcodekeeper.core.database.pg.schema.*;
import org.pgcodekeeper.core.database.pg.utils.PgConsts;
import org.pgcodekeeper.core.database.pg.utils.PgDiffUtils;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.telemetry.ComparisonTelemetryPublisher;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;
import org.pgcodekeeper.core.telemetry.PgConnectionLifecycleTelemetry;
import org.pgcodekeeper.core.telemetry.PgConnectionLifecycleTelemetry.Lifecycle;
import org.pgcodekeeper.core.telemetry.PgConnectionLifecycleTelemetry.LogicalSide;
import org.pgcodekeeper.core.telemetry.PgConnectionRole;
import org.pgcodekeeper.core.utils.Utils;
import org.postgresql.PGConnection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.pgcodekeeper.core.exception.XmlReaderException;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * JDBC-based database schema loader for PostgreSQL databases.
 * Reads database schemas, functions, views, tables, types, sequences, extensions, and other objects from a PostgreSQL database.
 * Supports timezone configuration and Greenplum database detection.
 * Extends JdbcLoaderBase to provide PostgreSQL-specific loading functionality.
 */
public class PgJdbcLoader extends AbstractJdbcLoader<PgDatabase> {

    private static final Logger LOG = LoggerFactory.getLogger(PgJdbcLoader.class);

    private static final String GREENPLUM = "Greenplum";
    private static final String GREENGAGE = "Greengage";
    private static final String EXTENSION_VERSION = "1.";

    static final String QUERY_CHECK_SERVER_VERSION = new QueryBuilder()
            .column("version() AS version_string")
            .column("CAST (pg_catalog.current_setting('server_version_num') AS INT) AS version_num")
            .build();

    private static final String QUERY_CHECK_USER_PRIVILEGES = new QueryBuilder()
            .column("pg_catalog.has_table_privilege('pg_catalog.pg_user_mapping', 'SELECT') AS result")
            .build();

    private static final String QUERY_CHECK_LAST_SYS_OID = new QueryBuilder()
            .column("datlastsysoid::bigint")
            .from("pg_catalog.pg_database")
            .where("datname = pg_catalog.current_database()")
            .build();

    private static final String QUERY_CHECK_TIMESTAMPS = new QueryBuilder()
            .column("n.nspname")
            .column("e.extversion")
            .column("EXISTS (SELECT 1 FROM pg_catalog.pg_event_trigger WHERE evtenabled != 'O' "
                    + "AND (evtname = 'dbots_tg_on_ddl_event' OR evtname = 'dbots_tg_on_drop_event')) AS disabled")
            .from("pg_catalog.pg_namespace n")
            .join("LEFT JOIN pg_catalog.pg_extension e on e.extnamespace = n.oid")
            .where("e.extname = 'pg_dbo_timestamp'")
            .build();

    static final String QUERY_TYPES_FOR_CACHE_ALL = new QueryBuilder()
            .column("t.oid")
            .column("t.typname")
            .column("t.typelem")
            .column("t.typarray")
            .column("t.typstorage")
            .column("t.typcollation::bigint")
            .column("n.nspname")
            .from("pg_catalog.pg_type t")
            .join("LEFT JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid")
            .build();

    static final String QUERY_COLLATIONS_FOR_CACHE_ALL = new QueryBuilder()
            .column("c.oid")
            .column("c.collname")
            .column("n.nspname")
            .from("pg_catalog.pg_collation c")
            .join("LEFT JOIN pg_catalog.pg_namespace n ON c.collnamespace = n.oid")
            .build();

    static final String QUERY_ROLES =
            "SELECT oid::bigint, rolname FROM pg_catalog.pg_roles";

    // cache reader names of the three loader-level snapshot queries; they are
    // hashed into the pack identity, so they must stay stable across releases
    private static final String TYPE_CACHE_READER = "PgTypeCacheReader";
    private static final String COLLATION_CACHE_READER = "PgCollationCacheReader";
    private static final String ROLE_CACHE_READER = "PgRoleCacheReader";

    /**
     * OID of the first user object
     *
     * @see <a href="https://github.com/postgres/postgres/blob/master/src/include/access/transam.h">transam.h</a>
     */
    private static final int FIRST_NORMAL_OBJECT_ID = 16384;
    protected final String timezone;

    private String extensionSchema;
    private boolean isGreenplumDb;
    private long lastSysOid;
    private Map<Long, PgJdbcType> cachedTypesByOid;
    private Map<Long, CachedCollation> cachedCollationsByOid;
    private PgJdbcRoutineBodyResolution routineBodyResolution;
    private ProjectRoutineBodyCatalogChannel projectRoutineBodyCatalog;
    private ComparisonSide comparisonSide;
    private PgCatalogRowCache catalogRowCache;
    private LogicalSide telemetrySide = LogicalSide.UNBOUND;
    private final Map<Connection, Integer> telemetryBackendPids =
            new IdentityHashMap<>();
    /**
     * One rollback boundary per catalog connection, shared by every derived
     * catalog probe of that connection. Lane connections are bound to one
     * worker thread each, but the primary connection is reachable from the
     * loader thread as well, so the map itself is guarded.
     */
    private final Map<Connection, Savepoint> catalogProbeSavepoints =
            new IdentityHashMap<>();

    /**
     * Creates a new PostgreSQL JDBC loader with the specified parameters.
     *
     * @param connector    the JDBC connector for establishing database connections
     * @param timezone     the timezone to set for the database connection
     * @param settings configuration settings
     */
    public PgJdbcLoader(IJdbcConnector connector, String timezone, ISettings settings) {
        super(connector, settings);
        this.timezone = timezone;
    }

    @Override
    public void preLoad() throws IOException, InterruptedException {
        try {
            checkCatalogReaderCancellation();
            if (isPreloaded) {
                checkCatalogReaderCancellation();
                return;
            }
            Connection ownedConnection = null;
            Statement ownedStatement = null;
            Throwable failure = null;
            try {
                ownedConnection = connector.getConnection();
                publishConnectionOpened(
                        ownedConnection, PgConnectionRole.PREFLIGHT, 0);
                registerActiveConnection(ownedConnection);
                this.connection = ownedConnection;
                checkCatalogReaderCancellation();

                ownedStatement = ownedConnection.createStatement();
                registerActiveStatement(ownedStatement);
                this.statement = ownedStatement;
                checkCatalogReaderCancellation();

                queryCheckServerVersion(ownedStatement);
            } catch (Exception | Error ex) {
                failure = ex;
            }

            publishConnectionCloseRequested(
                    ownedConnection, PgConnectionRole.PREFLIGHT, 0);
            failure = finishOwnedJdbcResources(ownedConnection, ownedStatement, failure);
            throwOwnedJdbcFailure(failure);
            publishPreloaded();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        }
    }

    @Override
    public PgDatabase loadInternal() throws IOException, InterruptedException {
        PgDatabase d = createDatabase();
        if (routineBodyResolution != null) {
            throw new IllegalStateException("Previous JDBC routine-body load is not released");
        }
        PgJdbcRoutineBodyResolution ownedRoutineBodyResolution = null;
        PgJdbcRoutineBodyCatalogMode bodyCatalogMode = PgJdbcRoutineBodyCatalogMode.FULL_BODY;

        info(Messages.JdbcLoader_log_reading_db_jdbc);
        setCurrentOperation(Messages.JdbcChLoader_log_connection_db);
        Connection ownedConnection = null;
        Statement ownedStatement = null;
        Throwable failure = null;
        try {
            checkCatalogReaderCancellation();
            ownedConnection = connector.getConnection();
            publishConnectionOpened(
                    ownedConnection, PgConnectionRole.PRIMARY, 0);
            registerActiveConnection(ownedConnection);
            this.connection = ownedConnection;
            checkCatalogReaderCancellation();

            ownedStatement = ownedConnection.createStatement();
            registerActiveStatement(ownedStatement);
            this.statement = ownedStatement;
            configureOwnedCatalogStatement(ownedStatement);
            checkCatalogReaderCancellation();

            ownedConnection.setAutoCommit(false);
            getRunner().run(ownedStatement, buildSessionSetupScript(timezone));

            bodyCatalogMode = PgRoutineBodyFingerprintCapability.detect(
                    requestRoutineBodyFingerprints(), this, ownedStatement);
            ISettings currentSettings = getSettings();
            String configuredCacheDirectory = currentSettings.getPgCatalogCacheDir();
            PgCatalogCacheNamespace.ResolvedIdentity cacheIdentity =
                    resolveCatalogCacheIdentity(
                    bodyCatalogMode, ownedConnection, ownedStatement,
                    configuredCacheDirectory,
                    currentSettings.isPgCatalogCacheRows(),
                    currentSettings.isPgCatalogCacheFingerprintProbe());
            Path targetCacheDirectory = cacheIdentity == null ? null
                    : cacheIdentity.namespace().resolveUnder(
                            Paths.get(configuredCacheDirectory));
            ownedRoutineBodyResolution = createRoutineBodyResolution(
                    bodyCatalogMode, targetCacheDirectory);
            routineBodyResolution = ownedRoutineBodyResolution;
            catalogRowCache = createCatalogRowCache(
                    targetCacheDirectory, cacheIdentity);

            queryCheckLastSysOid();
            queryTypesForCache();
            queryCollationsForCache();
            queryRoles();
            queryCheckExtension();

            info(Messages.JdbcLoader_log_read_db_objects);
            new PgSchemasReader(this, d).read();

            var sequencesReader = new PgSequencesReader(this);
            if (!readCatalogParallel(d, bodyCatalogMode, sequencesReader, ownedStatement)) {
                readCatalogSerial(d, bodyCatalogMode, sequencesReader, ownedStatement);
            }

            if (!PgSupportedVersion.GP_VERSION_7.isLE(getVersion())) {
                sequencesReader.querySequencesData(d);
            }
            if (catalogRowCache != null) {
                catalogRowCache.finishRun();
            }
            checkCatalogReaderCancellation();
            ownedRoutineBodyResolution.resolveAll(createRoutineBodyResolutionMonitor(getMonitor()));
            checkCatalogReaderCancellation();
            ownedConnection.commit();
            finishLoaders();

            d.sortColumns();
        } catch (Exception | Error ex) {
            failure = ex;
        }

        publishConnectionCloseRequested(
                ownedConnection, PgConnectionRole.PRIMARY, 0);
        failure = finishOwnedJdbcResources(ownedConnection, ownedStatement, failure);
        throwOwnedJdbcFailure(failure);
        info(Messages.JdbcLoader_log_succes_queried);
        return d;
    }

    /**
     * Reads the catalog with the sequential single-connection reader flow.
     * This is the reference flow: the lane-parallel flow must publish model
     * objects and analysis launchers in exactly this reader order.
     */
    private void readCatalogSerial(PgDatabase d, PgJdbcRoutineBodyCatalogMode bodyCatalogMode,
            PgSequencesReader sequencesReader, Statement ownedStatement)
            throws SQLException, InterruptedException, XmlReaderException {
        // NOTE: order of readers has been changed to move the heaviest ANTLR tasks to the beginning
        // to give them a chance to finish while JDBC processes other non-ANTLR stuff
        new PgFunctionsReader(this, bodyCatalogMode).read();
        if (!isGreenplumDb()) {
            // Greenplum loads aggregates through the combined functions query
            new PgAggregatesReader(this).read();
        }
        new PgViewsReader(this).read();
        new PgTablesReader(this).read();
        new PgRulesReader(this).read();
        if (PgSupportedVersion.GP_VERSION_7.isLE(getVersion())) {
            new PgPoliciesReader(this).read();
        }
        new PgTriggersReader(this).read();
        new PgIndicesReader(this).read();
        new PgConstraintsReader(this).read();
        new PgTypesReader(this).read();
        if (PgSupportedVersion.GP_VERSION_7.isLE(getVersion())) {
            new PgStatisticsReader(this).read();
        }

        // non-ANTLR tasks
        sequencesReader.read();
        new PgFtsParsersReader(this).read();
        new PgFtsTemplatesReader(this).read();
        new PgFtsDictionariesReader(this).read();
        new PgFtsConfigurationsReader(this).read();
        new PgOperatorsReader(this).read();

        new PgExtensionsReader(this, d).read();
        new PgEventTriggersReader(this, d).read();
        new PgCastsReader(this, d).read();
        new PgForeignDataWrappersReader(this, d).read();
        new PgServersReader(this, d).read();
        if (queryUserMappingsAllowed(ownedStatement)) {
            new PgUserMappingsReader(this, d).read();
        }
        new PgCollationsReader(this).read();
    }

    /**
     * Attempts the lane-parallel catalog reader flow. The parallel flow never
     * runs for Greenplum or with fewer than two configured workers, and it
     * falls back to the sequential flow when the shared-snapshot setup cannot
     * be established. A load-scoped row cache is shared safely by all lanes.
     *
     * @return {@code true} when the catalog was fully read in parallel
     */
    private boolean readCatalogParallel(PgDatabase d, PgJdbcRoutineBodyCatalogMode bodyCatalogMode,
            PgSequencesReader sequencesReader, Statement ownedStatement)
            throws SQLException, InterruptedException, XmlReaderException, IOException {
        int workers = getSettings().getPgParallelCatalogReaders();
        if (workers < 2 || isGreenplumDb()) {
            return false;
        }
        return new PgParallelCatalogReaders(this, d, bodyCatalogMode, sequencesReader,
                ownedStatement, workers).read();
    }

    /**
     * Runs the pg_user_mapping privilege probe on the primary statement.
     */
    boolean queryUserMappingsAllowed(Statement ownedStatement)
            throws SQLException, InterruptedException {
        try (ResultSet res = getRunner().runScript(ownedStatement, QUERY_CHECK_USER_PRIVILEGES)) {
            return res.next() && res.getBoolean("result");
        }
    }

    IJdbcConnector getConnector() {
        return connector;
    }

    String getTimezone() {
        return timezone;
    }

    void registerWorkerConnection(Connection workerConnection)
            throws IOException, InterruptedException {
        registerActiveConnection(workerConnection);
    }

    void clearWorkerConnection(Connection workerConnection) {
        clearActiveConnection(workerConnection);
    }

    void publishWorkerConnectionOpened(
            Connection workerConnection, int lane) {
        publishConnectionOpened(
                workerConnection, PgConnectionRole.CATALOG_LANE, lane);
    }

    void publishWorkerConnectionCloseRequested(
            Connection workerConnection, int lane) {
        publishConnectionCloseRequested(
                workerConnection, PgConnectionRole.CATALOG_LANE, lane);
    }

    /** Internal selection seam; capability detection remains authoritative. */
    protected boolean requestRoutineBodyFingerprints() {
        return requestRoutineBodyExchange() && hasProjectRoutineBodyCatalog();
    }

    /** Enables endpoint registration before a paired comparison starts. */
    protected boolean requestRoutineBodyExchange() {
        return getSettings().isPgRoutineBodyHashFirst();
    }

    @Override
    public void registerComparisonExtensions(ComparisonExtensionContext context) {
        telemetrySide = switch (
                Objects.requireNonNull(context, "context").side()) {
            case OLD -> LogicalSide.OLD;
            case NEW -> LogicalSide.NEW;
        };
        if (requestRoutineBodyExchange()) {
            PgRoutineBodyComparisonExtension.registerJdbc(context, this);
        }
    }

    private void publishConnectionOpened(
            Connection openedConnection, PgConnectionRole role, int lane) {
        if (openedConnection == null) {
            return;
        }
        IComparisonTelemetry telemetry =
                getSettings().getComparisonTelemetry();
        if (!ComparisonTelemetryPublisher.isEnabled(telemetry)) {
            return;
        }
        int backendPid = resolveBackendPid(openedConnection);
        telemetryBackendPids.put(openedConnection, backendPid);
        ComparisonTelemetryPublisher.publishPgConnection(telemetry,
                new PgConnectionLifecycleTelemetry(
                        telemetrySide, role, lane,
                        Lifecycle.OPENED, backendPid));
    }

    private void publishConnectionCloseRequested(
            Connection closingConnection, PgConnectionRole role, int lane) {
        if (closingConnection == null) {
            return;
        }
        Integer recordedPid = telemetryBackendPids.remove(closingConnection);
        IComparisonTelemetry telemetry =
                getSettings().getComparisonTelemetry();
        if (!ComparisonTelemetryPublisher.isEnabled(telemetry)) {
            return;
        }
        int backendPid = recordedPid == null
                ? resolveBackendPid(closingConnection)
                : recordedPid;
        ComparisonTelemetryPublisher.publishPgConnection(telemetry,
                new PgConnectionLifecycleTelemetry(
                        telemetrySide, role, lane,
                        Lifecycle.CLOSE_REQUESTED, backendPid));
    }

    private static int resolveBackendPid(Connection targetConnection) {
        try {
            PGConnection pgConnection =
                    targetConnection.unwrap(PGConnection.class);
            return pgConnection == null
                    ? 0
                    : Math.max(0, pgConnection.getBackendPID());
        } catch (SQLException | RuntimeException ex) {
            return 0;
        }
    }

    private PgCatalogCacheNamespace.ResolvedIdentity resolveCatalogCacheIdentity(
            PgJdbcRoutineBodyCatalogMode mode, Connection ownedConnection,
            Statement ownedStatement, String baseDirectory,
            boolean rowCacheEnabled, boolean snapshotProbeEnabled)
            throws SQLException, InterruptedException {
        if (baseDirectory == null
                || (!mode.isFingerprint() && !rowCacheEnabled)) {
            return null;
        }

        checkCatalogReaderCancellation();
        boolean supportsControlSystem = !isGreenplumDb
                && getVersion() >= 100000;
        CatalogIdentityAttempt[] attempts = identityAttempts(
                supportsControlSystem, snapshotProbeEnabled);
        if (attempts.length == 1) {
            return queryCatalogCacheIdentity(ownedStatement,
                    attempts[0].query(), attempts[0].systemTrusted());
        }

        // the boundary the ladder rolls back to is the one every later
        // catalog probe of this connection reuses, so the whole preparation
        // window opens exactly one subtransaction instead of one per probe
        ensureCatalogProbeSavepoint(ownedConnection);
        SQLException previousFailure = null;
        for (int i = 0; i < attempts.length; i++) {
            checkCatalogReaderCancellation();
            CatalogIdentityAttempt attempt = attempts[i];
            PgCatalogCacheNamespace.ResolvedIdentity identity;
            try {
                identity = queryCatalogCacheIdentity(ownedStatement,
                        attempt.query(), attempt.systemTrusted());
            } catch (SQLException failure) {
                rollbackCatalogIdentityProbe(ownedConnection, failure);
                if (i + 1 == attempts.length) {
                    if (previousFailure != null) {
                        failure.addSuppressed(previousFailure);
                    }
                    throw failure;
                }
                if (previousFailure == null) {
                    previousFailure = failure;
                } else {
                    previousFailure.addSuppressed(failure);
                }
                continue;
            }
            return identity;
        }
        throw new IllegalStateException("Catalog identity ladder is empty");
    }

    private static CatalogIdentityAttempt[] identityAttempts(
            boolean supportsControlSystem, boolean snapshotProbeEnabled) {
        CatalogIdentityAttempt safe = new CatalogIdentityAttempt(
                PgCatalogCacheNamespace.IDENTITY_QUERY_FALLBACK, false);
        if (!supportsControlSystem) {
            return snapshotProbeEnabled
                    ? new CatalogIdentityAttempt[] {
                            new CatalogIdentityAttempt(PgCatalogCacheNamespace
                                    .IDENTITY_QUERY_WITH_SNAPSHOT_FALLBACK,
                                    false),
                            safe
                    }
                    : new CatalogIdentityAttempt[] { safe };
        }
        CatalogIdentityAttempt system = new CatalogIdentityAttempt(
                PgCatalogCacheNamespace.IDENTITY_QUERY, true);
        if (!snapshotProbeEnabled) {
            return new CatalogIdentityAttempt[] { system, safe };
        }
        return new CatalogIdentityAttempt[] {
                new CatalogIdentityAttempt(PgCatalogCacheNamespace
                        .IDENTITY_QUERY_WITH_SNAPSHOT, true),
                system,
                new CatalogIdentityAttempt(PgCatalogCacheNamespace
                        .IDENTITY_QUERY_WITH_SNAPSHOT_FALLBACK, false),
                safe
        };
    }

    private void rollbackCatalogIdentityProbe(Connection probeConnection,
            SQLException probeFailure) throws SQLException {
        try {
            rollbackToCatalogProbeSavepoint(probeConnection);
        } catch (SQLException rollbackFailure) {
            probeFailure.addSuppressed(rollbackFailure);
            throw probeFailure;
        }
    }

    /**
     * Returns the shared rollback boundary of one catalog connection and
     * opens it on first use.
     * <p>
     * Every derived catalog statement - the cache-identity ladder and each
     * row-cache probe - needs a point the connection can return to after a
     * failure, before the caller may run plain SQL again. A boundary per
     * statement costs two extra round trips per reader, which on a
     * high-latency link is the single largest fixed cost of a warm
     * comparison. One boundary per
     * connection is enough: the comparison transaction is read-only and
     * repeatable-read, so returning to it discards no work, and PostgreSQL
     * keeps a savepoint valid after a rollback to it, so it stays reusable.
     * The boundary is opened after the session setup, never before, so a
     * rollback can never undo the session GUCs or an imported snapshot.
     *
     * @param probeConnection connection that runs the derived statement
     * @return shared savepoint of that connection
     * @throws SQLException if the boundary cannot be opened
     */
    public Savepoint ensureCatalogProbeSavepoint(Connection probeConnection)
            throws SQLException {
        Objects.requireNonNull(probeConnection, "probeConnection");
        synchronized (catalogProbeSavepoints) {
            if (catalogProbeSavepoints.containsKey(probeConnection)) {
                return catalogProbeSavepoints.get(probeConnection);
            }
        }
        Savepoint savepoint = probeConnection.setSavepoint();
        synchronized (catalogProbeSavepoints) {
            catalogProbeSavepoints.put(probeConnection, savepoint);
        }
        return savepoint;
    }

    /**
     * Returns one catalog connection to its shared probe boundary. The
     * boundary survives the rollback and stays available to later probes.
     *
     * @param probeConnection connection that ran the failed statement
     * @throws SQLException if the rollback fails
     */
    public void rollbackToCatalogProbeSavepoint(Connection probeConnection)
            throws SQLException {
        Savepoint savepoint;
        synchronized (catalogProbeSavepoints) {
            if (!catalogProbeSavepoints.containsKey(probeConnection)) {
                throw new IllegalStateException(
                        "Catalog probe boundary is not open on this connection");
            }
            savepoint = catalogProbeSavepoints.get(probeConnection);
        }
        probeConnection.rollback(savepoint);
    }

    /**
     * Closes the shared probe boundary of one catalog connection. Callers use
     * this before a statement whose effect must outlive every later catalog
     * probe, so that no rollback can reach past it.
     *
     * @param probeConnection connection whose boundary must be closed
     * @throws SQLException if the release fails
     */
    public void releaseCatalogProbeSavepoint(Connection probeConnection)
            throws SQLException {
        Savepoint savepoint;
        synchronized (catalogProbeSavepoints) {
            if (!catalogProbeSavepoints.containsKey(probeConnection)) {
                return;
            }
            savepoint = catalogProbeSavepoints.remove(probeConnection);
        }
        probeConnection.releaseSavepoint(savepoint);
    }

    private PgCatalogCacheNamespace.ResolvedIdentity queryCatalogCacheIdentity(
            Statement ownedStatement, String query,
            boolean systemIdentifierTrusted)
            throws SQLException, InterruptedException {
        try (ResultSet result = getRunner().runScript(ownedStatement, query)) {
            if (!result.next()) {
                throw new SQLException("Missing PostgreSQL catalog cache identity row");
            }
            PgCatalogCacheNamespace.ResolvedIdentity identity =
                    PgCatalogCacheNamespace.resolveIdentity(result,
                            systemIdentifierTrusted);
            if (result.next()) {
                throw new SQLException("Duplicate PostgreSQL catalog cache identity row");
            }
            checkCatalogReaderCancellation();
            return identity;
        }
    }

    private record CatalogIdentityAttempt(String query,
            boolean systemTrusted) {
    }

    private PgJdbcRoutineBodyResolution createRoutineBodyResolution(
            PgJdbcRoutineBodyCatalogMode mode, Path targetCacheDirectory) {
        ProjectRoutineBodyCatalogChannel catalog = takeProjectRoutineBodyCatalog();
        if (!mode.isFingerprint()) {
            if (catalog != null) {
                // A known capability fallback must decline before the project
                // side spends CPU and memory building an untaken catalog.
                catalog.cancel();
            }
            if (getSettings().getPgCatalogCacheDir() != null) {
                LOG.debug("PostgreSQL routine-body cache stays inactive without the "
                        + "hash-first fingerprint path");
            }
            return PgJdbcRoutineBodyResolution.fullBody();
        }
        ISettings currentSettings = getSettings();
        var limits = new PgRoutineBodyBatchLimits(
                currentSettings.getPgRoutineBodyResidualBatchCount(),
                currentSettings.getPgRoutineBodyResidualBatchBytes());
        PgRoutineBodyResidualTransport transport = createRoutineBodyResidualTransport();
        PgRoutineBodyCache bodyCache = createRoutineBodyCache(
                currentSettings, targetCacheDirectory);
        if (catalog == null && bodyCache == null) {
            return PgJdbcRoutineBodyResolution.fingerprint(transport, limits);
        }
        if (bodyCache == null && catalog != null) {
            // keep the simple two-stage resolver unless divergence applies
            PgRoutineBodyDivergencePolicy simplePolicy = createRoutineBodyDivergencePolicy();
            if (simplePolicy == null) {
                return PgJdbcRoutineBodyResolution.fingerprint(transport, limits, catalog);
            }
            return PgJdbcRoutineBodyResolution.fingerprint(
                    transport, limits, catalog, null, simplePolicy);
        }
        return PgJdbcRoutineBodyResolution.fingerprint(transport, limits, catalog,
                bodyCache, catalog == null ? null : createRoutineBodyDivergencePolicy());
    }

    /**
     * Builds the old-side fetch-skip policy for unmatched fingerprint slots.
     * The policy exists only when this loader's database model is the OLD
     * comparison side and the matched-body eligibility matrix admits at
     * least one late-bound representation; the master
     * {@code pgRoutineBodySkipMatchedAnalysis} switch disables it together
     * with the analysis skip.
     *
     * @return divergence policy, or null when every unmatched body must fetch
     */
    private PgRoutineBodyDivergencePolicy createRoutineBodyDivergencePolicy() {
        if (!isComparisonOldSide()) {
            return null;
        }
        ISettings currentSettings = getSettings();
        boolean plpgsqlEligible = PgFuncProcAnalysisLauncher.isSkipMatchedBodyAnalysisEligible(
                currentSettings, PgFuncProcAnalysisLauncher.BodyType.PLPGSQL);
        boolean sqlEligible = PgFuncProcAnalysisLauncher.isSkipMatchedBodyAnalysisEligible(
                currentSettings, PgFuncProcAnalysisLauncher.BodyType.SQL);
        if (!plpgsqlEligible && !sqlEligible) {
            return null;
        }
        return new PgRoutineBodyDivergencePolicy(plpgsqlEligible, sqlEligible);
    }

    private static PgRoutineBodyCache createRoutineBodyCache(
            ISettings settings, Path targetCacheDirectory) {
        if (targetCacheDirectory == null) {
            return null;
        }
        return new PgRoutineBodyCache(targetCacheDirectory,
                settings.getPgCatalogCacheMaxMb() << 20,
                settings.getComparisonTelemetry());
    }

    /**
     * Creates the row-level catalog cache when both the cache directory and
     * the row toggle are set. The system loader never reaches this seam: it
     * overrides {@code loadInternal} entirely, so its catalog reads always
     * run the plain path.
     */
    private PgCatalogRowCache createCatalogRowCache(
            Path targetCacheDirectory,
            PgCatalogCacheNamespace.ResolvedIdentity identity) {
        ISettings currentSettings = getSettings();
        if (targetCacheDirectory == null || !currentSettings.isPgCatalogCacheRows()) {
            return null;
        }
        LOG.debug("PostgreSQL catalog row cache is active in {}", targetCacheDirectory);
        return new PgCatalogRowCache(this, targetCacheDirectory,
                currentSettings.getPgCatalogCacheMaxMb() << 20,
                Objects.requireNonNull(identity, "identity").namespace(),
                currentSettings.isPgCatalogCacheFingerprintProbe(),
                identity.fingerprintTrustworthy()
                        ? identity.snapshotDigest() : null,
                currentSettings.getComparisonTelemetry());
    }

    @Override
    public ICatalogRowCache getCatalogRowCache() {
        return catalogRowCache;
    }

    /** Creates the same-snapshot transport after fingerprint capability succeeds. */
    protected PgRoutineBodyResidualTransport createRoutineBodyResidualTransport() {
        return new PgRoutineBodyResidualJdbcTransport(this);
    }

    synchronized void attachProjectRoutineBodyCatalog(
            ProjectRoutineBodyCatalogChannel catalog, ComparisonSide jdbcSide) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(jdbcSide, "jdbcSide");
        if (projectRoutineBodyCatalog != null) {
            throw new IllegalStateException(
                    "PostgreSQL project routine body catalog is already attached");
        }
        projectRoutineBodyCatalog = catalog;
        comparisonSide = jdbcSide;
    }

    synchronized void detachProjectRoutineBodyCatalog(
            ProjectRoutineBodyCatalogChannel catalog) {
        Objects.requireNonNull(catalog, "catalog");
        if (projectRoutineBodyCatalog == catalog) {
            projectRoutineBodyCatalog = null;
        } else if (projectRoutineBodyCatalog != null) {
            throw new IllegalStateException(
                    "Cannot detach a foreign PostgreSQL project routine body catalog");
        }
        comparisonSide = null;
    }

    /**
     * Returns whether the database model loaded by this loader acts as the
     * OLD side of a coordinated project-database comparison. The role is
     * known only through the comparison extension binding; standalone loads
     * (dump, export, library, DB-DB comparisons) never carry it.
     *
     * @return true only while attached as the OLD comparison side
     */
    public synchronized boolean isComparisonOldSide() {
        return comparisonSide == ComparisonSide.OLD;
    }

    private synchronized boolean hasProjectRoutineBodyCatalog() {
        return projectRoutineBodyCatalog != null;
    }

    private synchronized ProjectRoutineBodyCatalogChannel takeProjectRoutineBodyCatalog() {
        ProjectRoutineBodyCatalogChannel catalog = projectRoutineBodyCatalog;
        projectRoutineBodyCatalog = null;
        return catalog;
    }

    /**
     * Builds the one-round-trip session setup executed after autocommit is
     * disabled: pgJDBC sends the whole multi-command string in a single
     * network message, so the snapshot transaction starts with one exchange
     * instead of three.
     *
     * @param timezone session timezone value
     * @return combined SET script; SET TRANSACTION stays the first command
     */
    static String buildSessionSetupScript(String timezone) {
        return "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY; "
                + "SET search_path TO pg_catalog; "
                + "SET timezone = " + Utils.quoteString(timezone);
    }

    /**
     * Detects Greenplum and the numeric server version with one round trip.
     *
     * @param statement statement of the current connection
     */
    protected void queryCheckServerVersion(Statement statement) throws SQLException, InterruptedException {
        setCurrentOperation(Messages.JdbcLoaderBase_log_check_gp_db);
        String versionString = null;
        int version = PgSupportedVersion.GP_VERSION_6.getVersion();
        try (ResultSet res = getRunner().runScript(statement, QUERY_CHECK_SERVER_VERSION)) {
            if (res.next()) {
                versionString = res.getString("version_string");
                version = res.getInt("version_num");
            }
        }
        isGreenplumDb = versionString != null
                && (versionString.contains(GREENPLUM) || versionString.contains(GREENGAGE));
        debug(Messages.JdbcLoaderBase_log_get_result_gp, isGreenplumDb);

        setCurrentOperation(Messages.JdbcLoaderBase_log_reading_pg_version);
        setVersion(version);
        debug(Messages.JdbcLoaderBase_log_load_version, getVersion());
        if (!isGreenplumDb && !PgSupportedVersion.VERSION_14.isLE(version)) {
            throw new IllegalStateException(Messages.JdbcLoaderBase_unsupported_pg_version);
        }
        if (isGreenplumDb && !PgSupportedVersion.GP_VERSION_6.isLE(version)) {
            throw new IllegalStateException(Messages.JdbcLoaderBase_unsupported_gp_version);
        }
        settings.setVersion(PgSupportedVersion.valueOf(version));
    }

    protected void queryCheckLastSysOid() throws SQLException, InterruptedException {
        setCurrentOperation(Messages.JdbcLoaderBase_log_get_last_oid);
        if (PgSupportedVersion.VERSION_15.isLE(getVersion())) {
            lastSysOid = FIRST_NORMAL_OBJECT_ID - 1L;
        } else {
            try (ResultSet res = runner.runScript(statement, QUERY_CHECK_LAST_SYS_OID)) {
                lastSysOid = res.next() ? res.getLong(1) : 10_000;
            }
        }
        debug(Messages.JdbcLoaderBase_log_get_last_system_obj_oid, lastSysOid);
    }

    protected void queryTypesForCache() throws SQLException, InterruptedException {
        setCurrentOperation(Messages.JdbcLoaderBase_log_get_list_system_types);
        List<CachedTypeRow> rows = new ArrayList<>();
        readCatalogRows(TYPE_CACHE_READER, QUERY_TYPES_FOR_CACHE_ALL, res -> {
            IMonitor.checkCancelled(getMonitor());
            rows.add(new CachedTypeRow(res.getLong("oid"), res.getString("typname"),
                    res.getLong("typelem"), res.getLong("typarray"), res.getString("typstorage"),
                    res.getLong("typcollation"), res.getString("nspname")));
        });
        cachedTypesByOid = buildTypeCache(rows, lastSysOid);
    }

    protected void queryCollationsForCache() throws SQLException, InterruptedException {
        List<CachedCollationRow> rows = new ArrayList<>();
        readCatalogRows(COLLATION_CACHE_READER, QUERY_COLLATIONS_FOR_CACHE_ALL, res -> {
            IMonitor.checkCancelled(getMonitor());
            rows.add(new CachedCollationRow(res.getLong("oid"), res.getString("nspname"),
                    res.getString("collname")));
        });
        cachedCollationsByOid = buildCollationCache(rows);
    }

    /**
     * Reads one loader-level snapshot query through the row-level catalog
     * cache when it is active, and through the plain snapshot statement
     * otherwise. These queries carry the fixed part of a warm load: on a slow
     * link the full {@code pg_type} scan alone outweighs every cached reader.
     * <p>
     * The cache is fail-open: it consumes either every row or none, so the
     * plain path below still sees a complete result. Row values reach the
     * consumer through the same accessor subset the catalog readers use, so
     * the built caches are identical either way.
     *
     * @param readerName stable cache identity of this query
     * @param query      exact query the plain path would execute
     * @param consumer   per-row loop body of the plain path
     */
    private void readCatalogRows(String readerName, String query,
            ICatalogRowCache.CatalogRowConsumer consumer)
            throws SQLException, InterruptedException {
        ICatalogRowCache rowCache = catalogRowCache;
        if (rowCache != null) {
            try {
                if (rowCache.read(readerName, query, consumer, null)) {
                    return;
                }
            } catch (XmlReaderException ex) {
                // the loop bodies of these queries never parse SQL
                throw new IllegalStateException(
                        "Unexpected parser failure in a loader catalog query", ex);
            }
        }
        try (ResultSet res = runner.runScript(statement, query)) {
            while (res.next()) {
                try {
                    consumer.accept(res);
                } catch (XmlReaderException ex) {
                    throw new IllegalStateException(
                            "Unexpected parser failure in a loader catalog query", ex);
                }
            }
        }
    }

    /**
     * Builds the OID-keyed type cache, resolving array element type names
     * client-side from the same snapshot result set, including a null element
     * name for a missing element row.
     *
     * @param rows raw pg_type rows fetched in the current snapshot
     * @param lastSysOid last system object OID for dependency checks
     * @return mutable cache of types keyed by their OID
     */
    static Map<Long, PgJdbcType> buildTypeCache(List<CachedTypeRow> rows, long lastSysOid) {
        Map<Long, String> typeNamesByOid = new HashMap<>(capacityFor(rows.size()));
        for (CachedTypeRow row : rows) {
            typeNamesByOid.put(row.oid(), row.typname());
        }
        Map<Long, PgJdbcType> cache = new HashMap<>(capacityFor(rows.size()));
        for (CachedTypeRow row : rows) {
            String elemname = row.typarray() == 0L && row.typelem() != 0L
                    ? typeNamesByOid.get(row.typelem())
                    : null;
            cache.put(row.oid(), new PgJdbcType(row.oid(), row.typname(), row.typelem(),
                    row.typarray(), row.nspname(), elemname, lastSysOid, row.typstorage(),
                    row.typcollation()));
        }
        return cache;
    }

    static Map<Long, CachedCollation> buildCollationCache(List<CachedCollationRow> rows) {
        Map<Long, CachedCollation> cache = new HashMap<>(capacityFor(rows.size()));
        for (CachedCollationRow row : rows) {
            cache.put(row.oid(), new CachedCollation(row.nspname(), row.collname()));
        }
        return cache;
    }

    private static int capacityFor(int size) {
        return (int) (size / 0.75f) + 1;
    }

    /** One raw pg_type row of the global type-cache query. */
    record CachedTypeRow(long oid, String typname, long typelem, long typarray, String typstorage,
                         long typcollation, String nspname) {
    }

    /** One raw pg_collation row of the global collation-cache query. */
    record CachedCollationRow(long oid, String nspname, String collname) {
    }

    /** Immutable schema and name metadata for one cached collation OID. */
    public record CachedCollation(String schema, String name) {
    }

    protected void queryRoles() throws SQLException, InterruptedException {
        if (getSettings().isIgnorePrivileges()) {
            return;
        }
        cachedRolesNamesByOid = new HashMap<>();
        setCurrentOperation(Messages.JdbcLoaderBase_log_get_roles);
        readCatalogRows(ROLE_CACHE_READER, QUERY_ROLES, res -> {
            IMonitor.checkCancelled(getMonitor());
            cachedRolesNamesByOid.put(res.getLong("oid"), res.getString("rolname"));
        });
    }

    protected void queryCheckExtension() throws SQLException, InterruptedException {
        if (!getSettings().isReadAuthors()) {
            // authors are display-only metadata; leaving extensionSchema null
            // drops the dbots_event_data join from every catalog query
            return;
        }
        setCurrentOperation(Messages.JdbcLoaderBase_log_check_extension);
        try (ResultSet res = runner.runScript(statement, QUERY_CHECK_TIMESTAMPS)) {
            while (res.next()) {
                IMonitor.checkCancelled(getMonitor());
                String extVersion = res.getString("extversion");
                if (!extVersion.startsWith(EXTENSION_VERSION)) {
                    var msg = Messages.JdbcLoaderBase_log_old_version_used.formatted(extVersion,
                            EXTENSION_VERSION);
                    info(msg);
                } else if (res.getBoolean("disabled")) {
                    info(Messages.JdbcLoaderBase_log_event_trigger_disabled);
                } else {
                    extensionSchema = res.getString("nspname");
                }
            }
        }
    }

    public <T> void submitAntlrTask(String sql, Function<SQLParser, T> parserCtxReader, Consumer<T> finalizer) {
        BiFunction<List<Object>, String, SQLParser> createFunction =
                (list, location) -> PgParserUtils.createSqlParser(sql, location, list);
        submitAntlrTask(createFunction, parserCtxReader, finalizer);
    }

    public <T> void submitPlpgsqlTask(String sql, Function<SQLParser, T> parserCtxReader, Consumer<T> finalizer) {
        BiFunction<List<Object>, String, SQLParser> createFunction = (list, location) -> {
            var parser = PgParserUtils.createSqlParser(sql, location, list);
            PgParserUtils.removeIntoStatements(parser);
            return parser;
        };

        submitAntlrTask(createFunction, parserCtxReader, finalizer);
    }

    /**
     * Adds an already compact analysis launcher through the same ordered task
     * pipeline as parsed JDBC catalog expressions.
     *
     * @param launcher deferred analysis descriptor
     * @param db       database that receives the launcher in reader order
     */
    public void submitAnalysisLauncher(IAnalysisLauncher launcher, IDatabase db) {
        // a lane-parallel reader buffers into its canonical slot instead of
        // publishing directly, exactly like the wrapped ANTLR finalizers
        List<IAnalysisLauncher> sink = getCurrentLauncherSink();
        AntlrTaskManager.submit(getActiveAntlrTasks(), () -> launcher,
                sink == null ? db::addAnalysisLauncher : sink::add);
    }

    /**
     * Registers one attached catalog routine for deferred full-body
     * publication. The source is resolved at the loader barrier while the
     * current read-only repeatable-read snapshot is still active.
     *
     * @param routine final routine object already attached to its schema
     * @param raw raw parser input returned by PostgreSQL
     * @param canonical canonical body text stored in the database model
     * @param representation parser representation of the body
     * @return one-shot source owned by the analysis launcher; malformed input
     *         uses a local-only fallback
     */
    public RoutineBodySource registerFullBodyRoutineBody(
            PgAbstractFunction routine, String raw, String canonical,
            RoutineBodyRepresentation representation) {
        PgJdbcRoutineBodyResolution resolution = routineBodyResolution;
        if (resolution == null) {
            throw new IllegalStateException("JDBC routine-body load is not active");
        }
        return resolution.registerFullBody(routine, raw, canonical,
                RoutineBodyProfile.current(settings.isKeepNewlines()), representation);
    }

    /**
     * Registers bounded catalog metadata for one attached routine. Its raw
     * body is fetched and verified at the same-snapshot resolution barrier.
     *
     * @param routine final routine object already attached to its schema
     * @param bodyOid pg_proc OID used only inside the current snapshot
     * @param metadataOrdinal one-based catalog row ordinal
     * @param fingerprint exact raw UTF-8 length and SHA-256
     * @param representation parser representation of the body
     * @return one-shot source owned by the analysis launcher
     */
    public RoutineBodySource registerFingerprintRoutineBody(
            PgAbstractFunction routine, long bodyOid, long metadataOrdinal,
            RoutineFingerprint fingerprint, RoutineBodyRepresentation representation) {
        PgJdbcRoutineBodyResolution resolution = routineBodyResolution;
        if (resolution == null) {
            throw new IllegalStateException("JDBC routine-body load is not active");
        }
        return resolution.registerFingerprint(
                routine, bodyOid, metadataOrdinal, fingerprint,
                RoutineBodyProfile.current(settings.isKeepNewlines()), representation);
    }

    private IMonitor createRoutineBodyResolutionMonitor(IMonitor delegate) {
        return new IMonitor() {
            @Override
            public void setCancelled(boolean cancelled) {
                if (delegate != null) {
                    delegate.setCancelled(cancelled);
                }
            }

            @Override
            public boolean isCancelled() {
                return Thread.currentThread().isInterrupted()
                        || isCancellationRequested()
                        || isJdbcCancellationRequested()
                        || delegate != null && delegate.isCancelled();
            }

            @Override
            public void worked(int work) {
                if (delegate != null) {
                    delegate.worked(work);
                }
            }

            @Override
            public IMonitor createSubMonitor() {
                return createRoutineBodyResolutionMonitor(
                        delegate == null ? null : delegate.createSubMonitor());
            }

            @Override
            public void setWorkRemaining(int size) {
                if (delegate != null) {
                    delegate.setWorkRemaining(size);
                }
            }

            @Override
            public void setTaskName(String name) {
                if (delegate != null) {
                    delegate.setTaskName(name);
                }
            }
        };
    }

    public void setPrivileges(AbstractStatement st, String aclItemsArrayAsString, String schemaName) {
        setPrivileges(st, aclItemsArrayAsString, null, schemaName);
    }

    public void setPrivileges(PgColumn column, PgAbstractTable t, String aclItemsArrayAsString, String schemaName) {
        setPrivileges(column, PgDiffUtils.getQuotedName(t.getName()), aclItemsArrayAsString,
                t.getOwner(), PgDiffUtils.getQuotedName(column.getName()), schemaName);
    }

    public void setPrivileges(AbstractStatement st, String aclItemsArrayAsString, String columnName, String schemaName) {
        DbObjType type = st.getStatementType();
        String signature;
        if (type.in(DbObjType.FUNCTION, DbObjType.PROCEDURE, DbObjType.AGGREGATE)) {
            signature = ((PgAbstractFunction) st).appendFunctionSignature(new StringBuilder(), false, true).toString();
        } else {
            signature = PgDiffUtils.getQuotedName(st.getName());
        }

        String owner = st.getOwner();
        if (owner == null && type == DbObjType.SCHEMA && PgConsts.DEFAULT_SCHEMA.equals(st.getName())) {
            owner = "postgres";
        }

        setPrivileges(st, signature, aclItemsArrayAsString, owner,
                columnName == null ? null : PgDiffUtils.getQuotedName(columnName), schemaName);
    }

    public void setOwner(AbstractStatement statement, long ownerOid) {
        if (!getSettings().isIgnorePrivileges()) {
            statement.setOwner(getRoleByOid(ownerOid));
        }
    }

    /**
     * Parses <code>aclItemsArrayAsString</code> and adds parsed privileges to
     * <code>PgStatement</code> object. Owner privileges go first.
     * <br>
     * Currently supports privileges only on PgSequence, PgTable, PgView, PgColumn,
     * PgFunction, PgSchema, PgType, PgDomain
     *
     * @param st                    PgStatement object where privileges to be added
     * @param stSignature           PgStatement signature (differs in different PgStatement instances)
     * @param aclItemsArrayAsString Input acl string in the
     *                              form of "{grantee=grant_chars/grantor[, ...]}"
     * @param owner                 the owner of PgStatement object (why separate?)
     * @param columnId              column name, if this aclItemsArrayAsString is column
     *                              privilege string; otherwise null
     * @param schemaName            name of schema for 'PgStatement st'
     *
     * @see parseAclItem() in dumputils.c
     * <br>
     * For privilege characters see JdbcAclParser.PrivilegeTypes
     * <br>
     * Order of all characters (for all types of objects combined) : raxdtDXCcTUw
     */
    private void setPrivileges(AbstractStatement st, String stSignature,
                               String aclItemsArrayAsString, String owner, String columnId, String schemaName) {
        if (aclItemsArrayAsString == null || getSettings().isIgnorePrivileges()) {
            return;
        }
        DbObjType type = st.getStatementType();
        String stType = null;
        boolean isFunctionOrTypeOrDomain = false;
        String order;
        switch (type) {
            case SEQUENCE:
                order = "rUw";
                break;

            case TABLE, VIEW, COLUMN:
                stType = "TABLE";
                if (columnId != null) {
                    order = "raxw";
                } else if (PgSupportedVersion.VERSION_17.isLE(version)) {
                    order = "raxdtDwm";
                } else {
                    order = "raxdtDw";
                }
                break;

            case AGGREGATE:
                // For grant permissions to AGGREGATE in postgres used operator 'FUNCTION'.
                // For example grant permissions to AGGREGATE public.mode(boolean):
                // GRANT ALL ON FUNCTION public.mode(boolean) TO test_user
                stType = "FUNCTION";

                // For grant permissions to AGGREGATE without arguments as signature
                // used only left and right paren.
                if (stSignature.contains("*")) {
                    stSignature = stSignature.replace("*", "");
                }
                // $FALL-THROUGH$
            case FUNCTION, PROCEDURE:
                order = "X";
                isFunctionOrTypeOrDomain = true;
                break;

            case SCHEMA:
                order = "CU";
                break;

            case TYPE, DOMAIN:
                stType = "TYPE";
                order = "U";
                isFunctionOrTypeOrDomain = true;
                break;
            case SERVER:
                stType = "FOREIGN SERVER";
                order = "U";
                break;
            case FOREIGN_DATA_WRAPPER:
                stType = "FOREIGN DATA WRAPPER";
                order = "U";
                break;
            default:
                throw new IllegalStateException(type + Messages.JdbcLoaderBase_log_not_support_privil);
        }
        if (stType == null) {
            stType = st.getStatementType().name();
        }

        String qualStSignature = schemaName == null ? stSignature
                : PgDiffUtils.getQuotedName(schemaName) + '.' + stSignature;
        String column = columnId != null ? "(" + columnId + ")" : "";

        List<PgJdbcPrivilege> grants = PgJdbcPrivilege.parse(aclItemsArrayAsString, order, owner);

        boolean metPublicRoleGrants = false;
        boolean metDefaultOwnersGrants = false;
        for (PgJdbcPrivilege p : grants) {
            if (p.isGrantAllToPublic()) {
                metPublicRoleGrants = true;
            }
            if (p.isDefault()) {
                metDefaultOwnersGrants = true;
            }
        }

        // FUNCTION/TYPE/DOMAIN by default has "GRANT ALL to PUBLIC".
        // If "GRANT ALL to PUBLIC" for FUNCTION/TYPE/DOMAIN is absent, then
        // in this case for them explicitly added "REVOKE ALL from PUBLIC".
        if (!metPublicRoleGrants && isFunctionOrTypeOrDomain) {
            st.addPrivilege(new PgPrivilege("REVOKE", "ALL" + column,
                    stType + " " + qualStSignature, "PUBLIC", false));
        }

        // 'REVOKE ALL' for COLUMN never happened, because of the overlapping
        // privileges from the table.
        if (column.isEmpty() && !metDefaultOwnersGrants) {
            st.addPrivilege(new PgPrivilege("REVOKE", "ALL" + column,
                    stType + " " + qualStSignature, PgDiffUtils.getQuotedName(owner), false));
        }

        for (PgJdbcPrivilege grant : grants) {
            // Always add if statement type is COLUMN, because of the specific
            // relationship with table privileges.
            // The privileges of columns for role are not set lower than for the
            // same role in the parent table, they may be the same or higher.
            //
            // Skip if default owner's privileges
            // or if it is 'GRANT ALL ON FUNCTION/TYPE/DOMAIN schema.name TO PUBLIC'
            if (column.isEmpty() && (grant.isDefault() ||
                    (isFunctionOrTypeOrDomain && grant.isGrantAllToPublic()))) {
                continue;
            }
            st.addPrivilege(new PgPrivilege("GRANT", grant.getGrantString(column),
                    stType + " " + qualStSignature, grant.getGrantee(), grant.isGO()));
        }
    }

    public void setAuthor(AbstractStatement st, ResultSet res) throws SQLException {
        if (extensionSchema != null) {
            st.setAuthor(res.getString("ses_user"));
        }
    }

    public long getLastSysOid() {
        return lastSysOid;
    }

    public String getExtensionSchema() {
        return extensionSchema;
    }

    public boolean isGreenplumDb() {
        return isGreenplumDb;
    }

    public PgJdbcType getCachedTypeByOid(Long oid) {
        return cachedTypesByOid.get(oid);
    }

    public CachedCollation getCachedCollationByOid(Long oid) {
        return cachedCollationsByOid.get(oid);
    }

    public String getRoleByOid(long oid) {
        if (getSettings().isIgnorePrivileges()) {
            return null;
        }
        return oid == 0 ? "PUBLIC" : cachedRolesNamesByOid.get(oid);
    }

    @Override
    protected void releaseLoadResources() {
        PgJdbcRoutineBodyResolution resolution = routineBodyResolution;
        routineBodyResolution = null;
        try {
            if (resolution != null) {
                resolution.close();
            }
        } finally {
            super.releaseLoadResources();
            if (cachedTypesByOid != null) {
                cachedTypesByOid.clear();
                cachedTypesByOid = null;
            }
            if (cachedCollationsByOid != null) {
                cachedCollationsByOid.clear();
                cachedCollationsByOid = null;
            }
            projectRoutineBodyCatalog = null;
            comparisonSide = null;
            telemetrySide = LogicalSide.UNBOUND;
            telemetryBackendPids.clear();
            synchronized (catalogProbeSavepoints) {
                catalogProbeSavepoints.clear();
            }
            extensionSchema = null;
            catalogRowCache = null;
        }
    }

    @Override
    protected PgDatabase createDatabase() {
        return new PgDatabase(settings.isCollectObjectReferences());
    }
}
