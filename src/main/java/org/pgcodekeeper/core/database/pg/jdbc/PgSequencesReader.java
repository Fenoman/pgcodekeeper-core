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

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ISchema;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.jdbc.QueryBuilder;
import org.pgcodekeeper.core.database.base.schema.AbstractStatement;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractTable;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSequence;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reader for PostgreSQL sequences.
 * Loads sequence definitions from pg_class and related system catalogs.
 */
public final class PgSequencesReader extends PgAbstractSearchPathJdbcReader {

    private static final Logger LOG = LoggerFactory.getLogger(PgSequencesReader.class);

    private static final String QUERY_SEQUENCES_ACCESS = new QueryBuilder()
            .column("s.qname")
            .column("pg_catalog.has_sequence_privilege(s.qname, 'SELECT') AS has_priv")
            .from("(SELECT pg_catalog.unnest(?)) s(qname)")
            .build();

    private static final String QUERY_SCHEMAS_ACCESS = new QueryBuilder()
            .column("n.nspname")
            .column("pg_catalog.has_schema_privilege(n.nspname, 'USAGE') AS has_priv")
            .from("(SELECT pg_catalog.unnest(?)) n(nspname)")
            .build();

    private static final String QUERY_SEQUENCES_DATA = new QueryBuilder()
            .column("start_value")
            .column("increment_by")
            .column("max_value")
            .column("min_value")
            .column("cache_value")
            .column("is_cycled")
            .build();

    private final boolean includePrivileges;

    /**
     * Creates a new PgSequencesReader.
     *
     * @param loader the JDBC loader base for database operations
     */
    public PgSequencesReader(PgJdbcLoader loader) {
        super(loader);
        includePrivileges = !loader.getSettings().isIgnorePrivileges();
    }

    @Override
    protected void processResult(ResultSet res, ISchema schema) throws SQLException {
        String sequenceName = res.getString("relname");
        loader.setCurrentObject(new ObjectReference(schema.getName(), sequenceName, DbObjType.SEQUENCE));
        PgSequence s = new PgSequence(sequenceName);

        String refTable = res.getString("referenced_table_name");
        String refColumn = res.getString("ref_col_name");

        if ("u".equals(res.getString("relpersistence"))) {
            s.setLogged(false);
        }

        String identityType = null;
        if (PgSupportedVersion.GP_VERSION_7.isLE(loader.getVersion())) {
            identityType = res.getString("attidentity");
            if (identityType != null && identityType.isEmpty()) {
                // treat lack of table dependency and no identityType as a single case
                identityType = null;
            }

            s.setStartWith(Long.toString(res.getLong("seqstart")));
            String dataType = loader.getCachedTypeByOid(res.getLong("data_type")).getFullName();
            s.setMinMaxInc(res.getLong("seqincrement"), res.getLong("seqmax"),
                    res.getLong("seqmin"), dataType, 0L);
            s.setCache(Long.toString(res.getLong("seqcache")));
            s.setCycle(res.getBoolean("seqcycle"));
            s.setDataType(dataType);
        }

        if (refTable != null && identityType == null) {
            s.setOwnedBy(new ObjectReference(schema.getName(), refTable, refColumn, DbObjType.COLUMN));
        }

        if (identityType == null && includePrivileges) {
            loader.setOwner(s, res.getLong("relowner"));
            // PRIVILEGES
            loader.setPrivileges(s, res.getString("aclarray"), schema.getName());
        }

        loader.setComment(s, res);

        var isDefault = "d".equals(identityType);
        if (isDefault || "a".equals(identityType)) {
            PgAbstractTable table = (PgAbstractTable) schema.getChild(refTable, DbObjType.TABLE);
            if (table == null) {
                var msg = Messages.SequencesReader_log_not_found_table.formatted(refTable, s);
                LOG.error(msg);
                return;
            }
            PgColumn column = table.getColumn(refColumn);
            if (column == null) {
                column = new PgColumn(refColumn);
                column.setInherit(true);
                table.addColumn(column);
            }
            column.setSequence(s);
            s.setParent((AbstractStatement) schema);
            column.setIdentityType(isDefault ? "BY DEFAULT" : "ALWAYS");
        } else {
            loader.setAuthor(s, res);
            schema.addChild(s);
        }
    }

    public void querySequencesData(IDatabase db)
            throws SQLException, InterruptedException {
        loader.checkCatalogReaderCancellation();
        loader.setCurrentOperation(Messages.PgSequencesReader_sequences_data_query);
        IMonitor monitor = loader.getMonitor();

        List<String> schemasAccess = new ArrayList<>();
        PreparedStatement schemasAccessQuery = null;
        Array arrSchemas = null;
        ResultSet schemaRes = null;
        Throwable failure = null;
        try {
            schemasAccessQuery = loader.prepareCatalogStatement(QUERY_SCHEMAS_ACCESS);
            loader.registerCatalogStatement(schemasAccessQuery);
            arrSchemas = loader.getConnection().createArrayOf("text", db.getSchemas().stream()
                    .filter(s -> !((PgSchema) s).getSequences().isEmpty())
                    .map(ISchema::getName).toArray());
            schemasAccessQuery.setArray(1, arrSchemas);
            schemaRes = loader.getRunner().runScript(schemasAccessQuery);
            while (schemaRes.next()) {
                IMonitor.checkCancelled(monitor);
                String schema = schemaRes.getString("nspname");
                Object hasPriv = schemaRes.getObject("has_priv");
                IPgJdbcReader.checkObjectValidity(hasPriv, DbObjType.SCHEMA, schema);

                if ((boolean) hasPriv) {
                    schemasAccess.add(schema);
                } else {
                    loader.addError(Messages.PgSequencesReader_no_usage_privileges_for_schema.formatted(schema));
                }
            }
        } catch (SQLException | InterruptedException | RuntimeException | Error ex) {
            failure = ex;
        }
        finishPreparedRead(schemaRes, arrSchemas, schemasAccessQuery, failure);

        Map<String, PgSequence> seqs = new HashMap<>();
        for (String schema : schemasAccess) {
            for (PgSequence seq : ((PgSchema) db.getSchema(schema)).getSequences()) {
                seqs.put(seq.getQualifiedName(), seq);
            }
        }

        StringBuilder sbUnionQuery = new StringBuilder();
        PreparedStatement accessQuery = null;
        Array arrSeqs = null;
        ResultSet accessRes = null;
        failure = null;
        try {
            accessQuery = loader.prepareCatalogStatement(QUERY_SEQUENCES_ACCESS);
            loader.registerCatalogStatement(accessQuery);
            arrSeqs = loader.getConnection().createArrayOf("text", seqs.keySet().toArray());
            accessQuery.setArray(1, arrSeqs);
            accessRes = loader.getRunner().runScript(accessQuery);
            while (accessRes.next()) {
                IMonitor.checkCancelled(monitor);
                String qname = accessRes.getString("qname");
                Object hasPriv = accessRes.getObject("has_priv");
                IPgJdbcReader.checkObjectValidity(hasPriv, DbObjType.SEQUENCE, qname);

                if ((boolean) hasPriv) {
                    if (!sbUnionQuery.isEmpty()) {
                        sbUnionQuery.append("\nUNION ALL\n");
                    }
                    sbUnionQuery.append(QUERY_SEQUENCES_DATA)
                            .append(',')
                            .append(Utils.quoteString(qname))
                            .append(" qname FROM ")
                            .append(qname);
                } else {
                    loader.addError(Messages.PgSequencesReader_no_select_privileges_for_sequence.formatted(qname));
                }
            }
        } catch (SQLException | InterruptedException | RuntimeException | Error ex) {
            failure = ex;
        }
        finishPreparedRead(accessRes, arrSeqs, accessQuery, failure);

        loader.checkCatalogReaderCancellation();
        if (sbUnionQuery.isEmpty()) {
            return;
        }

        ResultSet res = null;
        failure = null;
        try {
            res = loader.getRunner().runScript(loader.getStatement(), sbUnionQuery.toString());
            while (res.next()) {
                IMonitor.checkCancelled(monitor);
                PgSequence seq = seqs.get(res.getString("qname"));
                seq.setStartWith(res.getString("start_value"));
                seq.setMinMaxInc(res.getLong("increment_by"), res.getLong("max_value"),
                        res.getLong("min_value"), null, 0L);
                seq.setCache(res.getString("cache_value"));
                seq.setCycle(res.getBoolean("is_cycled"));
            }
        } catch (SQLException | InterruptedException | RuntimeException | Error ex) {
            failure = ex;
        }
        finishOwnerResult(res, failure);
    }

    /**
     * Releases resources owned by one access query in cancellation-safe order. The JDBC array
     * remains live until its result set is closed, and the statement remains published until
     * both dependent resources are released. Identity-based merging avoids Java TWR
     * self-suppression when a driver rethrows the same failure instance from several steps.
     */
    private void finishPreparedRead(ResultSet result, Array array,
            PreparedStatement statement, Throwable failure)
            throws SQLException, InterruptedException {
        Throwable resultFailure = failure;
        if (result != null) {
            try {
                result.close();
            } catch (SQLException | RuntimeException | Error ex) {
                resultFailure = addFailure(resultFailure, ex);
            }
        }
        if (array != null) {
            try {
                array.free();
            } catch (SQLException | RuntimeException | Error ex) {
                resultFailure = addFailure(resultFailure, ex);
            }
        }
        if (statement != null) {
            try {
                loader.clearCatalogStatement(statement);
            } catch (RuntimeException | Error ex) {
                resultFailure = addFailure(resultFailure, ex);
            }
            try {
                statement.close();
            } catch (SQLException | RuntimeException | Error ex) {
                resultFailure = addFailure(resultFailure, ex);
            }
        }
        finishCatalogOperation(resultFailure);
    }

    /**
     * Closes only the result set produced by the loader-owned statement. The statement itself
     * stays registered and is closed by the enclosing loader operation.
     */
    private void finishOwnerResult(ResultSet result, Throwable failure)
            throws SQLException, InterruptedException {
        Throwable resultFailure = failure;
        if (result != null) {
            try {
                result.close();
            } catch (SQLException | RuntimeException | Error ex) {
                resultFailure = addFailure(resultFailure, ex);
            }
        }
        finishCatalogOperation(resultFailure);
    }

    private void finishCatalogOperation(Throwable failure)
            throws SQLException, InterruptedException {
        if (failure != null) {
            InterruptedException cancellation = loader.classifyCatalogReaderCancellation(failure);
            if (cancellation != null) {
                throw cancellation;
            }
            rethrowCatalogFailure(failure);
        }
        loader.checkCatalogReaderCancellation();
    }

    private static Throwable addFailure(Throwable primary, Throwable secondary) {
        if (secondary == null) {
            return primary;
        }
        if (primary == null) {
            return secondary;
        }
        if (primary == secondary) {
            return primary;
        }
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == secondary) {
                return primary;
            }
        }
        primary.addSuppressed(secondary);
        return primary;
    }

    private static void rethrowCatalogFailure(Throwable failure)
            throws SQLException, InterruptedException {
        if (failure instanceof SQLException sql) {
            throw sql;
        }
        if (failure instanceof InterruptedException interrupted) {
            throw interrupted;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unexpected checked sequence reader failure", failure);
    }

    @Override
    public String getClassId() {
        return "pg_class";
    }

    @Override
    protected String getSchemaColumn() {
        return "res.relnamespace";
    }

    @Override
    protected void fillQueryBuilder(QueryBuilder builder) {
        addExtensionDepsCte(builder);
        addDescriptionPart(builder, true);

        if (includePrivileges) {
            builder.column("res.relowner::bigint");
        }
        builder
                .column("res.relname")
                .column("res.relpersistence")
                .column("(SELECT t.relname FROM pg_catalog.pg_class t WHERE t.oid=dep.refobjid) referenced_table_name")
                .column("a.attname AS ref_col_name");
        if (includePrivileges) {
            builder.column("res.relacl::text AS aclarray");
        }
        builder
                .from("pg_catalog.pg_class res")
                .join("LEFT JOIN pg_catalog.pg_depend dep ON dep.classid = res.tableoid AND dep.objid = res.oid AND dep.objsubid = 0"
                        + " AND dep.refclassid = res.tableoid AND dep.refobjsubid != 0 AND dep.deptype IN ('i', 'a')")
                .join("LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid = dep.refobjid AND a.attnum = dep.refobjsubid AND a.attisdropped IS FALSE")
                .where("res.relkind = 'S'");

        if (PgSupportedVersion.GP_VERSION_7.isLE(loader.getVersion())) {
            builder
                    .column("s.seqtypid::bigint AS data_type")
                    .column("s.seqstart")
                    .column("s.seqincrement")
                    .column("s.seqmax")
                    .column("s.seqmin")
                    .column("s.seqcache")
                    .column("s.seqcycle")
                    .column("a.attidentity")
                    .join("LEFT JOIN pg_catalog.pg_sequence s ON s.seqrelid = res.oid");
        }
    }
}
