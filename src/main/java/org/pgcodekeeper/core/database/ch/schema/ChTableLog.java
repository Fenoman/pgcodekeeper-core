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
package org.pgcodekeeper.core.database.ch.schema;

import java.util.*;

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.base.schema.*;
import org.pgcodekeeper.core.hasher.Hasher;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Represents a ClickHouse Log family table that supports constraints.
 * Extends ChTable with the ability to add CHECK and ASSUME constraints.
 */
public class ChTableLog extends ChTable {

    private final List<ChConstraint> constrs = new ArrayList<>();

    /**
     * Creates a new ClickHouse Log table with the specified name.
     *
     * @param name the name of the table
     */
    public ChTableLog(String name) {
        super(name);
    }

    @Override
    protected void appendTableBody(StringBuilder sb, ISettings settings) {
        super.appendTableBody(sb, settings);
        for (var constr : constrs) {
            sb.append("\n\tCONSTRAINT ").append(constr.getQuotedName()).append(' ')
                    .append(constr.getDefinition()).append(',');
        }
    }

    @Override
    protected boolean isNeedRecreate(ChTable newTable) {
        var newChLogTable = (ChTableLog) newTable;
        return super.isNeedRecreate(newChLogTable)
                || !constrs.equals(newChLogTable.constrs)
                || !columns.equals(newChLogTable.columns);
    }

    @Override
    public void addChild(IStatement st) {
        if (st.getStatementType() == DbObjType.CONSTRAINT) {
            constrs.add((ChConstraint) st);
            resetHash();
            return;
        }
        super.addChild(st);
    }

    /**
     * The {@code CHECK} of a Log table is written inside the body of the
     * {@code CREATE} as the text it came in, and is held neither among the
     * constraints of this table nor among its children -
     * {@link #getConstraints()} does not report it and no walk of the database
     * reaches it - so nothing else can speak for the columns it reads. A column
     * one of them names therefore cannot be left out: the body would state a
     * column it does not declare.
     * <hr><br>
     * {@inheritDoc}
     */
    @Override
    public Collection<String> getClausesNamingColumns() {
        if (constrs.isEmpty()) {
            return super.getClausesNamingColumns();
        }

        List<String> clauses = new ArrayList<>(super.getClausesNamingColumns());
        constrs.forEach(constr -> clauses.add(constr.getDefinition()));
        return clauses;
    }

    @Override
    public void computeHash(Hasher hasher) {
        super.computeHash(hasher);
        hasher.putOrdered(constrs);
    }

    @Override
    public boolean compare(IStatement obj) {
        return this == obj || super.compare(obj);
    }

    @Override
    protected boolean compareTable(AbstractStatement obj) {
        return obj instanceof ChTableLog table
                && super.compareTable(table)
                && Objects.equals(constrs, table.constrs);
    }

    @Override
    protected ChTableLog getTableCopy() {
        var table = new ChTableLog(name);
        table.constrs.addAll(constrs);
        return table;
    }
}