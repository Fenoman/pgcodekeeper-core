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
package org.pgcodekeeper.core.database.pg.parser.launcher;

import java.util.Set;

import org.antlr.v4.runtime.ParserRuleContext;
import org.pgcodekeeper.core.database.api.schema.IColumn;
import org.pgcodekeeper.core.database.api.schema.ISubElement;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.meta.IMetaContainer;
import org.pgcodekeeper.core.database.base.schema.*;
import org.pgcodekeeper.core.database.pg.parser.expr.PgValueExpr;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.VexContext;
import org.pgcodekeeper.core.database.pg.parser.rulectx.PgVex;

/**
 * Launcher for analyzing value expressions (VEX) in SQL definitions.
 * Handles both column expressions and standalone value expressions.
 */
public class PgVexAnalysisLauncher extends PgAbstractAnalysisLauncher {

    /**
     * Creates a value expression analyzer.
     *
     * @param stmt     the statement containing the expression
     * @param ctx      the value expression context to analyze
     * @param location the source location identifier
     */
    public PgVexAnalysisLauncher(AbstractStatement stmt, VexContext ctx, String location) {
        super(stmt, ctx, location);
    }

    @Override
    public Set<ObjectLocation> analyze(ParserRuleContext ctx, IMetaContainer meta) {
        if (isWrittenInsideATable()) {
            return analyzeTableChildVex((VexContext) ctx, meta);
        }

        PgValueExpr expr = new PgValueExpr(meta);
        expr.analyze(new PgVex((VexContext) ctx));
        return expr.getDependencies();
    }

    /**
     * Whether the expression belongs to the definition of a table, and its
     * unqualified names are therefore the columns of that table.
     * <p>
     * A default and a generated expression are written on a column, and the
     * {@code USING} and {@code WITH CHECK} of a policy are written on the table
     * itself - all of them read the row the statement is about. Without the
     * namespace of that table an unqualified name resolves to nothing at all:
     * {@code findColumn} has no parent scope to ask at the top level, and the
     * whole expression yields no dependency, which is how a policy came to depend
     * on none of the columns it reads. A default of a function argument or of a
     * domain, the other users of this launcher, are written outside any table and
     * keep the bare namespace they had.
     * <p>
     * The same question the ClickHouse launcher asks, and it is asked of the two
     * interfaces rather than of {@code PgPolicy} because a constraint and an index
     * are {@code ISubElement} too and already answer it the same way through
     * launchers of their own.
     */
    private boolean isWrittenInsideATable() {
        return stmt instanceof IColumn || stmt instanceof ISubElement;
    }
}
