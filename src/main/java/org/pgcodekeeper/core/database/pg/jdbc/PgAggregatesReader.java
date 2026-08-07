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

import org.pgcodekeeper.core.database.base.jdbc.QueryBuilder;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;

/**
 * Reader for PostgreSQL aggregates. Carries the pg_aggregate lookup joins
 * separately from the routine query because aggregates are a tiny minority
 * of pg_proc rows. Greenplum uses the combined query in
 * {@link PgFunctionsReader} and does not use this reader.
 */
public final class PgAggregatesReader extends PgFunctionsReader {

    /**
     * Creates a new aggregates reader.
     *
     * @param loader the JDBC loader base for database operations
     */
    public PgAggregatesReader(PgJdbcLoader loader) {
        super(loader);
    }

    @Override
    protected void fillQueryBuilder(QueryBuilder builder) {
        addExtensionDepsCte(builder);
        addDescriptionPart(builder);

        builder.column("res.proname");
        if (includePrivileges) {
            builder.column("res.proowner::bigint");
        }
        builder
                .column("res.prorettype::bigint")
                .column("res.proallargtypes::bigint[]")
                .column("res.proargmodes")
                .column("res.proargnames");
        if (includePrivileges) {
            builder.column("res.proacl::text AS aclarray");
        }
        builder
                .column("res.proretset")
                .column("array(select pg_catalog.unnest(res.proargtypes))::bigint[] as argtypes")
                .column("res.pronargs")
                .column("res.proparallel")
                .column("TRUE AS proisagg")
                .column("a.aggfinalmodify AS finalfunc_modify")
                .column("a.aggmfinalmodify AS mfinalfunc_modify")
                .from("pg_catalog.pg_proc res");
        addAggregatePart(builder, "JOIN pg_catalog.pg_aggregate a ON a.aggfnoid = res.oid");
        builder
                .where("res.prokind = 'a'")
                .orderBy("res.oid");
    }
}
