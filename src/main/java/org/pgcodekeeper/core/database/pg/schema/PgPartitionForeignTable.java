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
package org.pgcodekeeper.core.database.pg.schema;

import java.util.Objects;

import org.pgcodekeeper.core.database.api.schema.IPartitionTable;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.hasher.Hasher;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Partition foreign table object for PostgreSQL.
 * Represents a partition of a foreign table, which allows partitioning
 * of data across foreign servers while maintaining the partitioning structure.
 *
 * @author galiev_mr
 * @since 4.1.1
 */
public class PgPartitionForeignTable extends PgAbstractForeignTable implements IPartitionTable {

    private final String partitionBounds;

    /**
     * The bound as the comparison sees it, built the same way and for the same
     * reason as {@code PgPartitionTable.partitionBoundsNormalized}: both sides
     * of the comparison state the same bound in different words, and only the
     * database side is written by the server.
     * <p>
     * {@link #partitionBounds} keeps the text the DDL is written from.
     */
    private final String partitionBoundsNormalized;

    /**
     * Creates a new partition foreign table.
     *
     * @param name                      table name
     * @param serverName                foreign server name
     * @param partitionBounds           partition bounds definition, as written
     * @param partitionBoundsNormalized the same bound normalized for comparison
     */
    public PgPartitionForeignTable(String name, String serverName, String partitionBounds,
                                   String partitionBoundsNormalized) {
        super(name, serverName);
        this.partitionBounds = partitionBounds;
        this.partitionBoundsNormalized = partitionBoundsNormalized;
    }

    @Override
    public String getPartitionBounds() {
        return partitionBounds;
    }

    @Override
    public String getParentTable() {
        return inherits.get(0).getQualifiedName();
    }

    @Override
    protected boolean isNeedRecreate(PgAbstractTable newTable, ISettings settings) {
        return super.isNeedRecreate(newTable, settings)
                || !(Objects.equals(partitionBoundsNormalized,
                        ((PgPartitionForeignTable) newTable).partitionBoundsNormalized))
                || !inherits.equals(newTable.inherits);
    }

    @Override
    protected void appendColumns(StringBuilder sbSQL, SQLScript script) {
        sbSQL.append(" PARTITION OF ").append(getParentTable());

        if (!columns.isEmpty()) {
            sbSQL.append(" (\n");

            int start = sbSQL.length();
            writeColumns(sbSQL, script);

            if (start != sbSQL.length()) {
                sbSQL.setLength(sbSQL.length() - 2);
                sbSQL.append("\n)");
            } else {
                sbSQL.setLength(sbSQL.length() - 3);
            }
        }

        sbSQL.append('\n');
        sbSQL.append(partitionBounds);
    }

    @Override
    protected void appendInherit(StringBuilder sb) {
        // PgTable.inherits stores PARTITION OF table in this implementation
    }

    @Override
    public void computeHash(Hasher hasher) {
        super.computeHash(hasher);
        hasher.put(partitionBoundsNormalized);
    }

    @Override
    public boolean compare(IStatement obj) {
        return this == obj || super.compare(obj);
    }

    @Override
    protected boolean compareTable(PgAbstractTable obj) {
        return obj instanceof PgPartitionForeignTable table
                && super.compareTable(table)
                && Objects.equals(partitionBoundsNormalized, table.partitionBoundsNormalized);
    }

    @Override
    protected PgAbstractTable getTableCopy() {
        return new PgPartitionForeignTable(name, serverName, partitionBounds, partitionBoundsNormalized);
    }
}
