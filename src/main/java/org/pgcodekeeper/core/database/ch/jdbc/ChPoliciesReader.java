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
package org.pgcodekeeper.core.database.ch.jdbc;

import java.sql.*;

import org.antlr.v4.runtime.CommonTokenStream;
import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.base.jdbc.*;
import org.pgcodekeeper.core.database.ch.loader.ChJdbcLoader;
import org.pgcodekeeper.core.database.ch.parser.statement.ChCreatePolicy;
import org.pgcodekeeper.core.database.ch.schema.*;
import org.pgcodekeeper.core.utils.Pair;

/**
 * Reader for ClickHouse policies.
 * Loads policy definitions from system.row_policies table.
 */
public class ChPoliciesReader extends AbstractJdbcReader<ChJdbcLoader> {

    private final ChDatabase db;

    /**
     * Creates a new ChPoliciesReader.
     *
     * @param loader the JDBC loader instance
     * @param db     the ClickHouse database to load policies into
     */
    public ChPoliciesReader(ChJdbcLoader loader, ChDatabase db) {
        super(loader);
        this.db = db;
    }

    @Override
    protected void processResult(ResultSet res) throws SQLException {
        String policyName = res.getString("name");

        loader.setCurrentObject(new ObjectReference(policyName, DbObjType.POLICY));

        ChPolicy p = new ChPolicy(policyName);

        ChJdbcUtils.addRoles(res, "apply_to_list", "apply_to_except", p, ChPolicy::addRole, ChPolicy::addExcept);

        p.setPermissive(!res.getBoolean("is_restrictive"));

        String using = res.getString("select_filter");
        if (using != null) {
            // the catalog's own text is stored here and unconditionally, because
            // the task below is finalized only when this task's parse reported
            // no errors (AbstractJdbcLoader:377). A filter this grammar cannot
            // read must still reach the model: without it the policy would be
            // written out as a CREATE POLICY with no USING at all, dropping the
            // row restriction, which is a far worse answer than a policy that
            // reads as changed
            //
            // both halves get it, and the normalized one must not be left empty:
            // compare and computeHash read only that half, so an unreadable
            // filter would otherwise compare equal to no filter at all and the
            // difference would vanish from the tree and from the script. On a
            // successful parse setUsingWithAnalyze overwrites it with the real
            // normalization
            p.setUsing(using, using);

            // the filter arrives as bare text, so it is re-parsed - as this
            // reader already did for the analysis launcher - and the stream
            // travels with the context because submitChAntlrTask extracts the
            // one in a call that has ended before the other is consumed
            loader.submitChAntlrTask(using,
                    parser -> new Pair<>(parser.expr_eof(), (CommonTokenStream) parser.getTokenStream()),
                    pair -> ChCreatePolicy.setUsingWithAnalyze(p, using, pair.getFirst().expr(),
                            pair.getSecond(), db, loader.getCurrentLocation()));
        }

        db.addChild(p);
    }

    @Override
    protected void fillQueryBuilder(QueryBuilder builder) {
        builder
                .column("res.name")
                .column("res.is_restrictive")
                .column("res.select_filter")
                .column("res.apply_to_list")
                .column("res.apply_to_except")
                .from("system.row_policies res");
    }
}
