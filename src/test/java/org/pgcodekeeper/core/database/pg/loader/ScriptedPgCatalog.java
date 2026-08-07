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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.pgcodekeeper.core.database.pg.jdbc.PgCatalogCacheNamespace;
import org.pgcodekeeper.core.database.pg.jdbc.PgFakeCatalogArrays;

/**
 * Scripted thread-safe JDBC stand-in for a PostgreSQL catalog. Queries are
 * matched by distinctive SQL fragments in registration order and answered
 * from fixed row fixtures, which makes repeated loads byte-reproducible for
 * the serial-versus-parallel determinism tests.
 */
final class ScriptedPgCatalog implements IJdbcConnector {

    private final List<QueryRule> rules = new ArrayList<>();
    private final List<String> executedScripts = Collections.synchronizedList(new ArrayList<>());
    private final List<JdbcEvent> jdbcEvents = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final AtomicInteger connectionCloseCount = new AtomicInteger();
    private final AtomicInteger cacheIdentityQueryCount = new AtomicInteger();
    private final AtomicInteger savepointOpenCount = new AtomicInteger();
    private final AtomicInteger savepointRollbackCount = new AtomicInteger();
    private final AtomicInteger savepointReleaseCount = new AtomicInteger();
    private final Map<String, Object> cacheIdentity = Collections.synchronizedMap(
            defaultCacheIdentity());
    private final String password;

    private volatile int maxConnections = Integer.MAX_VALUE;
    private volatile boolean failSnapshotExport;
    private volatile boolean failPrimarySnapshotExport;
    private volatile String cancelColdCacheFragment;
    private volatile Runnable cancelColdCacheAction;
    private final AtomicBoolean coldCacheCancellationArmed = new AtomicBoolean();

    ScriptedPgCatalog() {
        this("fixture-password");
    }

    ScriptedPgCatalog(String password) {
        this.password = password;
    }

    /** Registers rows for every query containing the fragment. First match wins. */
    void on(String sqlFragment, List<Map<String, Object>> rows) {
        rules.add(new QueryRule(sqlFragment, rows));
    }

    void failSnapshotExport() {
        this.failSnapshotExport = true;
    }

    void failPrimarySnapshotExport() {
        failPrimarySnapshotExport = true;
    }

    void limitConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    void setCacheIdentity(String column, String value) {
        if (!cacheIdentity.containsKey(column)) {
            throw new IllegalArgumentException("Unknown cache identity column: " + column);
        }
        cacheIdentity.put(column, value);
    }

    void cancelAfterFirstColdCacheRow(String sqlFragment, Runnable action) {
        cancelColdCacheFragment = sqlFragment;
        cancelColdCacheAction = action;
        coldCacheCancellationArmed.set(true);
    }

    /** Number of catalog probe boundaries opened across every connection. */
    int getSavepointOpenCount() {
        return savepointOpenCount.get();
    }

    /** Number of rollbacks to a catalog probe boundary. */
    int getSavepointRollbackCount() {
        return savepointRollbackCount.get();
    }

    /** Number of released catalog probe boundaries. */
    int getSavepointReleaseCount() {
        return savepointReleaseCount.get();
    }

    int getConnectionCount() {
        return connectionCount.get();
    }

    int getConnectionCloseCount() {
        return connectionCloseCount.get();
    }

    int getCacheIdentityQueryCount() {
        return cacheIdentityQueryCount.get();
    }

    List<String> getExecutedScripts() {
        return executedScripts;
    }

    List<JdbcEvent> getJdbcEvents() {
        synchronized (jdbcEvents) {
            return List.copyOf(jdbcEvents);
        }
    }

    @Override
    public Connection getConnection() throws IOException {
        int identity = connectionCount.incrementAndGet();
        if (identity > maxConnections) {
            throw new IOException("Scripted connection limit reached");
        }
        var handler = new ConnectionHandler(identity);
        Connection connection = proxy(Connection.class, handler);
        handler.connection = connection;
        return connection;
    }

    @Override
    public String getBatchDelimiter() {
        return null;
    }

    @Override
    public String getUrl() {
        return "jdbc:postgresql://scripted/test?password=" + password;
    }

    @Override
    public String getDbName() {
        return "scripted";
    }

    private List<Map<String, Object>> dispatch(String sql, byte[] wantedHashes)
            throws SQLException {
        boolean baseIdentity = PgCatalogCacheNamespace.IDENTITY_QUERY.equals(sql);
        boolean baseFallback = PgCatalogCacheNamespace.IDENTITY_QUERY_FALLBACK
                .equals(sql);
        boolean snapshotIdentity = PgCatalogCacheNamespace
                .IDENTITY_QUERY_WITH_SNAPSHOT.equals(sql);
        boolean snapshotFallback = PgCatalogCacheNamespace
                .IDENTITY_QUERY_WITH_SNAPSHOT_FALLBACK.equals(sql);
        if (baseIdentity || baseFallback || snapshotIdentity
                || snapshotFallback) {
            cacheIdentityQueryCount.incrementAndGet();
            synchronized (cacheIdentity) {
                Map<String, Object> identity = new LinkedHashMap<>(cacheIdentity);
                if (baseFallback || snapshotFallback) {
                    identity.put("system_identifier", null);
                }
                if (baseIdentity || baseFallback) {
                    identity.put("snapshot_token", null);
                }
                return List.of(identity);
            }
        }
        if ((failSnapshotExport || failPrimarySnapshotExport)
                && sql.contains("pg_export_snapshot")) {
            throw new SQLException("Scripted snapshot export failure");
        }
        for (QueryRule rule : rules) {
            if (sql.contains(rule.fragment())) {
                return wrapCacheRows(sql, rule, wantedHashes);
            }
        }
        throw new SQLException("Unexpected scripted query: " + sql);
    }

    private static Map<String, Object> defaultCacheIdentity() {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("server_address", "192.0.2.10");
        identity.put("server_port", "5432");
        identity.put("database_name", "scripted");
        identity.put("database_oid", "16384");
        identity.put("system_identifier", "7504815387372040237");
        identity.put("session_user_name", "scripted_user");
        identity.put("current_role_name", "scripted_role");
        identity.put("server_version_num", "160400");
        identity.put("timezone", "UTC");
        identity.put("date_style", "ISO, MDY");
        identity.put("interval_style", "postgres");
        identity.put("extra_float_digits", "1");
        identity.put("bytea_output", "hex");
        identity.put("quote_all_identifiers", "off");
        identity.put("snapshot_token", "100:200:");
        return identity;
    }

    private static List<Map<String, Object>> wrapCacheRows(
            String sql, QueryRule rule, byte[] wantedHashes) {
        boolean hashedRows = sql.startsWith(
                "SELECT pg_catalog.decode(pg_catalog.md5(__pgck_r::text), "
                        + "'hex') AS __pgck_h, __pgck_r.*");
        boolean hashOnly = sql.startsWith(
                "SELECT ((__pgck_o - 1) / 4096)::bigint AS __pgck_c");
        boolean missFetch = sql.contains("__pgck_wanted");
        if (!hashedRows && !hashOnly && !missFetch) {
            return rule.rows();
        }

        if (hashOnly) {
            List<String> hashes = new ArrayList<>(rule.rows().size());
            for (int i = 0; i < rule.rows().size(); i++) {
                hashes.add(rowHash(rule.fragment(), i, rule.rows().get(i)));
            }
            List<Map<String, Object>> chunks = new ArrayList<>();
            for (int from = 0, ordinal = 0; from < hashes.size();
                    from += 4096, ordinal++) {
                int to = Math.min(from + 4096, hashes.size());
                String packed = String.join("", hashes.subList(from, to));
                Map<String, Object> wrapped = new LinkedHashMap<>();
                wrapped.put("__pgck_c", (long) ordinal);
                wrapped.put("__pgck_n", (long) (to - from));
                wrapped.put("__pgck_h", HexFormat.of().parseHex(packed));
                wrapped.put("__pgck_t", (long) hashes.size());
                chunks.add(wrapped);
            }
            return chunks;
        }

        List<String> wanted = unpackHashes(wantedHashes);
        List<Map<String, Object>> result = new ArrayList<>();
        if (missFetch) {
            Map<String, Map<String, Object>> rowsByHash = new LinkedHashMap<>();
            for (int i = 0; i < rule.rows().size(); i++) {
                rowsByHash.put(rowHash(rule.fragment(), i, rule.rows().get(i)),
                        rule.rows().get(i));
            }
            for (String hash : wanted) {
                Map<String, Object> row = rowsByHash.get(hash);
                if (row != null) {
                    result.add(wrappedRow(hash, row));
                }
            }
            return result;
        }
        for (int i = 0; i < rule.rows().size(); i++) {
            Map<String, Object> row = rule.rows().get(i);
            String hash = rowHash(rule.fragment(), i, row);
            result.add(wrappedRow(hash, row));
        }
        return result;
    }

    private static List<String> unpackHashes(byte[] packed) {
        if (packed == null) {
            return List.of();
        }
        if (packed.length % 16 != 0) {
            throw new IllegalArgumentException("Invalid packed catalog hash payload");
        }
        List<String> hashes = new ArrayList<>(packed.length / 16);
        for (int offset = 0; offset < packed.length; offset += 16) {
            hashes.add(HexFormat.of().formatHex(packed, offset, offset + 16));
        }
        return hashes;
    }

    private static Map<String, Object> wrappedRow(String hash,
            Map<String, Object> row) {
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("__pgck_h", HexFormat.of().parseHex(hash));
        wrapped.putAll(row);
        return wrapped;
    }

    private static String rowHash(String fragment, int ordinal, Map<String, Object> row) {
        StringBuilder stable = new StringBuilder(fragment).append('\0').append(ordinal);
        row.forEach((key, value) -> stable.append('\0').append(key).append('=')
                .append(Arrays.deepToString(new Object[] {value})));
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(
                    digest.digest(stable.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 is unavailable", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(ScriptedPgCatalog.class.getClassLoader(),
                new Class<?>[] { type }, handler);
    }

    private record QueryRule(String fragment, List<Map<String, Object>> rows) {
    }

    enum JdbcEventKind {
        SCRIPT,
        QUERY,
        ARRAY,
        SAVEPOINT_OPEN,
        SAVEPOINT_ROLLBACK,
        SAVEPOINT_RELEASE
    }

    record JdbcEvent(int connectionIdentity, JdbcEventKind kind, String sql,
                     boolean snapshotImported) {
    }

    /** Base handler with identity object semantics and lenient defaults. */
    private abstract static class BaseHandler implements InvocationHandler {

        @Override
        public final Object invoke(Object proxyInstance, Method method, Object[] args)
                throws Throwable {
            switch (method.getName()) {
                case "equals":
                    return proxyInstance == args[0];
                case "hashCode":
                    return System.identityHashCode(proxyInstance);
                case "toString":
                    return getClass().getSimpleName();
                default:
                    return handle(method, args);
            }
        }

        abstract Object handle(Method method, Object[] args) throws Throwable;

        static Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive() || returnType == void.class) {
                return null;
            }
            if (returnType == boolean.class) {
                return Boolean.FALSE;
            }
            if (returnType == float.class) {
                return 0f;
            }
            if (returnType == double.class) {
                return 0d;
            }
            if (returnType == long.class) {
                return 0L;
            }
            return 0;
        }
    }

    private final class ConnectionHandler extends BaseHandler {

        private final int identity;
        private Connection connection;
        private volatile boolean snapshotImported;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile boolean probeSavepointActive;

        private ConnectionHandler(int identity) {
            this.identity = identity;
        }

        @Override
        Object handle(Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "createStatement" -> proxy(Statement.class,
                        new StatementHandler(null, this));
                case "prepareStatement" -> proxy(PreparedStatement.class,
                        new StatementHandler((String) args[0], this));
                case "setSavepoint" -> {
                    if (probeSavepointActive) {
                        throw new SQLException(
                                "Scripted catalog probe boundary is already open");
                    }
                    probeSavepointActive = true;
                    savepointOpenCount.incrementAndGet();
                    jdbcEvents.add(new JdbcEvent(identity,
                            JdbcEventKind.SAVEPOINT_OPEN, null, snapshotImported));
                    yield proxy(Savepoint.class,
                            new SavepointHandler(identity));
                }
                case "rollback" -> {
                    if (args != null && args.length == 1) {
                        if (!probeSavepointActive) {
                            throw new SQLException(
                                    "Scripted catalog probe boundary is not open");
                        }
                        savepointRollbackCount.incrementAndGet();
                        jdbcEvents.add(new JdbcEvent(identity,
                                JdbcEventKind.SAVEPOINT_ROLLBACK, null,
                                snapshotImported));
                    }
                    yield null;
                }
                case "releaseSavepoint" -> {
                    if (!probeSavepointActive) {
                        throw new SQLException(
                                "Scripted catalog probe boundary is not open");
                    }
                    probeSavepointActive = false;
                    savepointReleaseCount.incrementAndGet();
                    jdbcEvents.add(new JdbcEvent(identity,
                            JdbcEventKind.SAVEPOINT_RELEASE, null,
                            snapshotImported));
                    yield null;
                }
                case "createArrayOf" -> {
                    jdbcEvents.add(new JdbcEvent(identity, JdbcEventKind.ARRAY, null,
                            snapshotImported));
                    yield proxy(Array.class, new ArrayHandler(args[1]));
                }
                case "close" -> {
                    if (closed.compareAndSet(false, true)) {
                        connectionCloseCount.incrementAndGet();
                    }
                    yield null;
                }
                case "isClosed" -> closed.get();
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    private final class StatementHandler extends BaseHandler {

        private final String preparedSql;
        private final ConnectionHandler owner;
        private Object[] arrayParameter;
        private byte[] bytesParameter;

        private StatementHandler(String preparedSql, ConnectionHandler owner) {
            this.preparedSql = preparedSql;
            this.owner = owner;
        }

        @Override
        Object handle(Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "execute": {
                    String script = args != null && args.length > 0
                            ? (String) args[0] : preparedSql;
                    if (failSnapshotExport && script != null
                            && script.contains("pg_export_snapshot")) {
                        throw new SQLException("Scripted snapshot export failure");
                    }
                    if (script != null && script.contains("pg_export_snapshot")
                            && owner.probeSavepointActive) {
                        throw new SQLException(
                                "Cannot export a snapshot from the scripted "
                                        + "identity subtransaction");
                    }
                    executedScripts.add(script);
                    jdbcEvents.add(new JdbcEvent(owner.identity, JdbcEventKind.SCRIPT, script,
                            owner.snapshotImported));
                    if (script != null && script.contains("SET TRANSACTION SNAPSHOT")) {
                        owner.snapshotImported = true;
                    }
                    return Boolean.FALSE;
                }
                case "executeQuery": {
                    String sql = args != null && args.length > 0 ? (String) args[0] : preparedSql;
                    if (sql != null && sql.contains("pg_export_snapshot")
                            && owner.probeSavepointActive) {
                        throw new SQLException(
                                "Cannot export a snapshot from the scripted "
                                        + "identity subtransaction");
                    }
                    jdbcEvents.add(new JdbcEvent(owner.identity, JdbcEventKind.QUERY, sql,
                            owner.snapshotImported));
                    return proxy(ResultSet.class,
                            new ResultSetHandler(dispatch(sql, bytesParameter),
                                    cancellationAction(sql)));
                }
                case "setArray":
                    arrayParameter = (Object[]) ((Array) args[1]).getArray();
                    return null;
                case "setBytes":
                    bytesParameter = ((byte[]) args[1]).clone();
                    return null;
                case "getConnection":
                    return owner.connection;
                case "isClosed":
                    return Boolean.FALSE;
                default:
                    return defaultValue(method.getReturnType());
            }
        }

        private Runnable cancellationAction(String sql) {
            String fragment = cancelColdCacheFragment;
            if (fragment != null && sql != null && sql.startsWith(
                    "SELECT pg_catalog.decode(pg_catalog.md5(__pgck_r::text), "
                            + "'hex') AS __pgck_h, __pgck_r.*")
                    && !sql.contains("__pgck_wanted") && sql.contains(fragment)
                    && coldCacheCancellationArmed.compareAndSet(true, false)) {
                return cancelColdCacheAction;
            }
            return null;
        }
    }

    private static final class ResultSetHandler extends BaseHandler {

        private final List<Map<String, Object>> rows;
        private final Runnable cancelBeforeSecondRow;
        private int cursor = -1;

        private ResultSetHandler(List<Map<String, Object>> rows,
                Runnable cancelBeforeSecondRow) {
            this.rows = rows;
            this.cancelBeforeSecondRow = cancelBeforeSecondRow;
        }

        @Override
        Object handle(Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "next":
                    boolean hasNext = ++cursor < rows.size();
                    if (hasNext && cursor == 1 && cancelBeforeSecondRow != null) {
                        cancelBeforeSecondRow.run();
                    }
                    return hasNext;
                case "getString": {
                    // pgJDBC renders an array column as its PostgreSQL text
                    Object value = value(args[0]);
                    return value instanceof Object[] elements
                            ? PgFakeCatalogArrays.render(elements) : value;
                }
                case "getObject": {
                    // and serves the same column as java.sql.Array
                    Object value = value(args[0]);
                    return value instanceof Object[] elements
                            ? proxy(Array.class, new ArrayHandler(elements))
                            : value;
                }
                case "getBytes":
                    return value(args[0]);
                case "getBinaryStream": {
                    byte[] bytes = (byte[]) value(args[0]);
                    return bytes == null ? null
                            : new ByteArrayInputStream(bytes);
                }
                case "getLong": {
                    Object value = value(args[0]);
                    return value == null ? 0L : ((Number) value).longValue();
                }
                case "getInt": {
                    Object value = value(args[0]);
                    return value == null ? 0 : ((Number) value).intValue();
                }
                case "getFloat": {
                    Object value = value(args[0]);
                    return value == null ? 0f : ((Number) value).floatValue();
                }
                case "getBoolean": {
                    Object value = value(args[0]);
                    return value != null && (Boolean) value;
                }
                case "getArray": {
                    Object value = value(args[0]);
                    return value == null ? null : proxy(Array.class, new ArrayHandler(value));
                }
                case "getMetaData":
                    return proxy(ResultSetMetaData.class, new ResultSetMetaDataHandler(rows));
                case "isClosed":
                    return Boolean.FALSE;
                default:
                    return defaultValue(method.getReturnType());
            }
        }

        private Object value(Object column) throws SQLException {
            Map<String, Object> row = rows.get(cursor);
            if (column instanceof Integer index) {
                return new ArrayList<>(row.values()).get(index - 1);
            }
            String name = (String) column;
            if (!row.containsKey(name)) {
                throw new SQLException("Scripted row has no column: " + name);
            }
            return row.get(name);
        }
    }

    private static final class ResultSetMetaDataHandler extends BaseHandler {

        private final List<String> labels;

        private ResultSetMetaDataHandler(List<Map<String, Object>> rows) {
            labels = rows.isEmpty() ? List.of("__pgck_h")
                    : List.copyOf(rows.get(0).keySet());
        }

        @Override
        Object handle(Method method, Object[] args) {
            return switch (method.getName()) {
                case "getColumnCount" -> labels.size();
                case "getColumnLabel", "getColumnName" -> labels.get((Integer) args[0] - 1);
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    /** Named stand-in for the shared catalog probe boundary of one connection. */
    private static final class SavepointHandler extends BaseHandler {

        private final int connectionIdentity;

        private SavepointHandler(int connectionIdentity) {
            this.connectionIdentity = connectionIdentity;
        }

        @Override
        Object handle(Method method, Object[] args) {
            return switch (method.getName()) {
                case "getSavepointName" -> "pgck_catalog_probe_" + connectionIdentity;
                case "getSavepointId" -> connectionIdentity;
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    private static final class ArrayHandler extends BaseHandler {

        private final Object array;

        private ArrayHandler(Object array) {
            this.array = array;
        }

        @Override
        Object handle(Method method, Object[] args) {
            if ("getArray".equals(method.getName())) {
                return array;
            }
            return defaultValue(method.getReturnType());
        }
    }
}
