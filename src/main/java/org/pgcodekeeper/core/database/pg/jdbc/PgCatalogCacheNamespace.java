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
package org.pgcodekeeper.core.database.pg.jdbc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable, secret-free namespace for PostgreSQL persistent catalog caches.
 * One target fingerprint selects the directory shared by the routine-body and
 * row stores; reader qualifiers then separate exact reader/query/codec
 * identities within that target.
 */
public final class PgCatalogCacheNamespace {

    static final int CACHE_ABI = 2;
    static final String TARGET_PREFIX = "target-v2-";

    private static final String IDENTITY_QUERY_PREFIX =
            "SELECT COALESCE(pg_catalog.inet_server_addr()::text, 'local') AS server_address,\n"
            + "       COALESCE(pg_catalog.inet_server_port(), -1) AS server_port,\n"
            + "       pg_catalog.current_database() AS database_name,\n"
            + "       (SELECT d.oid::text FROM pg_catalog.pg_database d "
            + "WHERE d.datname = pg_catalog.current_database()) AS database_oid,\n";

    private static final String IDENTITY_QUERY_SUFFIX =
            "       session_user AS session_user_name,\n"
            + "       current_user AS current_role_name,\n"
            + "       pg_catalog.current_setting('server_version_num') AS server_version_num,\n"
            + "       pg_catalog.current_setting('TimeZone') AS timezone,\n"
            + "       pg_catalog.current_setting('DateStyle') AS date_style,\n"
            + "       pg_catalog.current_setting('IntervalStyle') AS interval_style,\n"
            + "       pg_catalog.current_setting('extra_float_digits') AS extra_float_digits,\n"
            + "       pg_catalog.current_setting('bytea_output') AS bytea_output,\n"
            + "       pg_catalog.current_setting('quote_all_identifiers') AS quote_all_identifiers,\n";

    /** Direct system-identity probe used by the client-side fallback ladder. */
    public static final String IDENTITY_QUERY = identityQuery(
            "((pg_catalog.pg_control_system()).system_identifier)::text",
            "NULL::text");

    /**
     * Fast path that directly probes both optional identity functions.
     * <p>
     * The snapshot token is deliberately a visibility marker rather than a
     * digest of the catalog, and it is deliberately kept out of the target
     * digest below. Why equality of it is enough to serve a pack unverified,
     * and what that costs, is argued where the decision is taken, at the
     * snapshot comparison in {@code PgCatalogRowCache.tryWarm}.
     */
    public static final String IDENTITY_QUERY_WITH_SNAPSHOT = identityQuery(
            "((pg_catalog.pg_control_system()).system_identifier)::text",
            "pg_catalog.txid_current_snapshot()::text");

    /** Fully safe identity query that references neither optional function. */
    public static final String IDENTITY_QUERY_FALLBACK = identityQuery(
            "NULL::text", "NULL::text");

    /** Direct snapshot-only probe after the system function is unavailable. */
    public static final String IDENTITY_QUERY_WITH_SNAPSHOT_FALLBACK =
            identityQuery("NULL::text",
                    "pg_catalog.txid_current_snapshot()::text");

    private final int cacheAbi;
    private final String targetDigest;

    private PgCatalogCacheNamespace(int cacheAbi, String targetDigest) {
        this.cacheAbi = cacheAbi;
        this.targetDigest = targetDigest;
    }

    private static String identityQuery(String systemIdentifier,
            String snapshotToken) {
        return IDENTITY_QUERY_PREFIX + "       " + systemIdentifier
                + " AS system_identifier,\n" + IDENTITY_QUERY_SUFFIX
                + "       " + snapshotToken + " AS snapshot_token";
    }

    /**
     * Reads the current row of the supplied result set. Query execution and
     * result-set positioning remain the loader's responsibility.
     */
    public static PgCatalogCacheNamespace fromResultSet(ResultSet result) throws SQLException {
        return resolveIdentity(result, true).namespace();
    }

    /** Resolves target identity and the current repeatable-read snapshot. */
    public static ResolvedIdentity resolveIdentity(ResultSet result,
            boolean systemIdentifierTrusted) throws SQLException {
        Objects.requireNonNull(result, "result");
        String systemIdentifier = result.getString("system_identifier");
        boolean trustworthy = systemIdentifierTrusted
                && systemIdentifier != null && !systemIdentifier.isBlank();
        PgCatalogCacheNamespace namespace = fromValues(CACHE_ABI,
                result.getString("server_address"),
                result.getString("server_port"),
                result.getString("database_name"),
                result.getString("database_oid"),
                trustworthy ? systemIdentifier : "unavailable",
                result.getString("session_user_name"),
                result.getString("current_role_name"),
                result.getString("server_version_num"),
                result.getString("timezone"),
                result.getString("date_style"),
                result.getString("interval_style"),
                result.getString("extra_float_digits"),
                result.getString("bytea_output"),
                result.getString("quote_all_identifiers"));
        String snapshotToken = result.getString("snapshot_token");
        byte[] snapshotDigest = snapshotToken == null || snapshotToken.isBlank()
                ? null : newSha256().digest(
                        snapshotToken.getBytes(StandardCharsets.UTF_8));
        return new ResolvedIdentity(namespace, snapshotDigest,
                trustworthy && snapshotDigest != null);
    }

    /** Test seam for deterministic target-identity construction. */
    static PgCatalogCacheNamespace fromValues(int cacheAbi,
            String serverAddress, String serverPort, String databaseName,
            String sessionUserName, String currentRoleName, String serverVersionNum,
            String timezone, String dateStyle, String intervalStyle,
            String extraFloatDigits, String byteaOutput,
            String quoteAllIdentifiers) {
        return fromValues(cacheAbi, serverAddress, serverPort, databaseName,
                "0", "unavailable", sessionUserName, currentRoleName,
                serverVersionNum, timezone, dateStyle, intervalStyle,
                extraFloatDigits, byteaOutput, quoteAllIdentifiers);
    }

    static PgCatalogCacheNamespace fromValues(int cacheAbi,
            String serverAddress, String serverPort, String databaseName,
            String databaseOid, String systemIdentifier,
            String sessionUserName, String currentRoleName, String serverVersionNum,
            String timezone, String dateStyle, String intervalStyle,
            String extraFloatDigits, String byteaOutput,
            String quoteAllIdentifiers) {
        if (cacheAbi <= 0) {
            throw new IllegalArgumentException("Catalog cache ABI must be positive");
        }
        String digest = digestPairs(
                "cache_abi", Integer.toString(cacheAbi),
                "server_address", requireIdentityValue("server_address", serverAddress),
                "server_port", requireIdentityValue("server_port", serverPort),
                "database_name", requireIdentityValue("database_name", databaseName),
                "database_oid", requireIdentityValue("database_oid", databaseOid),
                "system_identifier", requireIdentityValue(
                        "system_identifier", systemIdentifier),
                "session_user_name", requireIdentityValue("session_user_name", sessionUserName),
                "current_role_name", requireIdentityValue("current_role_name", currentRoleName),
                "server_version_num", requireIdentityValue(
                        "server_version_num", serverVersionNum),
                "timezone", requireIdentityValue("timezone", timezone),
                "date_style", requireIdentityValue("date_style", dateStyle),
                "interval_style", requireIdentityValue("interval_style", intervalStyle),
                "extra_float_digits", requireIdentityValue(
                        "extra_float_digits", extraFloatDigits),
                "bytea_output", requireIdentityValue("bytea_output", byteaOutput),
                "quote_all_identifiers", requireIdentityValue(
                        "quote_all_identifiers", quoteAllIdentifiers));
        return new PgCatalogCacheNamespace(cacheAbi, digest);
    }

    /** Resolves this target's single opaque child below the configured base. */
    public Path resolveUnder(Path baseDirectory) {
        return Objects.requireNonNull(baseDirectory, "baseDirectory")
                .resolve(TARGET_PREFIX + targetDigest);
    }

    /** Returns the full opaque qualifier for one exact catalog reader query. */
    String readerQualifier(String readerName, String exactQuery, byte rowCodecVersion) {
        return "n" + digestPairs(
                "cache_abi", Integer.toString(cacheAbi),
                "reader_name", requireIdentityValue("reader_name", readerName),
                "exact_query", requireIdentityValue("exact_query", exactQuery),
                "row_codec_version", Integer.toString(Byte.toUnsignedInt(rowCodecVersion)));
    }

    private static String requireIdentityValue(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Catalog cache identity field must not be blank: " + field);
        }
        return value;
    }

    private static String digestPairs(String... components) {
        MessageDigest digest = newSha256();
        for (String component : components) {
            byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
            int length = bytes.length;
            digest.update((byte) (length >>> 24));
            digest.update((byte) (length >>> 16));
            digest.update((byte) (length >>> 8));
            digest.update((byte) length);
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /** Target namespace plus snapshot context, with defensive digest access. */
    public record ResolvedIdentity(PgCatalogCacheNamespace namespace,
            byte[] snapshotDigest, boolean fingerprintTrustworthy) {

        public ResolvedIdentity {
            Objects.requireNonNull(namespace, "namespace");
            snapshotDigest = snapshotDigest == null
                    ? null : snapshotDigest.clone();
            if (fingerprintTrustworthy && snapshotDigest == null) {
                throw new IllegalArgumentException(
                        "Trustworthy fingerprint requires a snapshot digest");
            }
        }

        @Override
        public byte[] snapshotDigest() {
            return snapshotDigest == null ? null : snapshotDigest.clone();
        }
    }
}
