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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.IPrivilege;
import org.pgcodekeeper.core.hasher.Hasher;
import org.pgcodekeeper.core.script.SQLActionType;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

class PgSequenceOwnerOrderingTest {

    @Test
    void ownerChangeRebuildsStandaloneSequenceAclAfterOwnerAndBeforeCommit() {
        PgSequence oldSequence = sequence("seq", "old_owner");
        PgSequence newSequence = sequence("seq", "new_owner");
        newSequence.addPrivilege(new PgPrivilege(
                "GRANT", "USAGE", "SEQUENCE public.seq", "old_owner", false));

        var script = new SQLScript(new CoreSettings(), ";");
        oldSequence.appendAlterSQL(newSequence, script);
        script.addStatement("COMMIT TRANSACTION", SQLActionType.END);

        assertEquals("""
                ALTER SEQUENCE public.seq OWNER TO new_owner;

                REVOKE ALL ON SEQUENCE public.seq FROM PUBLIC;

                REVOKE ALL ON SEQUENCE public.seq FROM new_owner;

                GRANT ALL ON SEQUENCE public.seq TO new_owner;

                GRANT USAGE ON SEQUENCE public.seq TO old_owner;

                COMMIT TRANSACTION;""", script.getFullScript());
    }

    @Test
    void identitySequenceRenameUsesTargetNameForLateOwnerChange() {
        PgColumn oldColumn = identityColumn("old_seq", "old_owner");
        PgColumn newColumn = identityColumn("new_seq", "new_owner");

        var script = new SQLScript(new CoreSettings(), ";");
        oldColumn.appendAlterSQL(newColumn, script);
        String sql = script.getFullScript();

        String rename = "ALTER SEQUENCE public.old_seq RENAME TO new_seq;";
        String targetOwner = "ALTER SEQUENCE public.new_seq OWNER TO new_owner;";
        assertAll(
                () -> assertTrue(sql.contains(rename), sql),
                () -> assertTrue(sql.contains(targetOwner), sql),
                () -> assertFalse(sql.contains(
                        "ALTER SEQUENCE public.old_seq OWNER TO new_owner;"), sql),
                () -> assertTrue(sql.indexOf(rename) < sql.indexOf(targetOwner), sql));
    }

    @Test
    void identitySequenceRenameRetargetsAclAfterOwnerChange() {
        PgColumn oldColumn = identityColumn("old_seq", "old_owner");
        oldColumn.getSequence().addPrivilege(new PgPrivilege(
                "GRANT", "USAGE", "SEQUENCE public.old_seq", "reader", false));
        PgColumn newColumn = identityColumn("new_seq", "new_owner");
        newColumn.getSequence().addPrivilege(new PgPrivilege(
                "GRANT", "SELECT", "SEQUENCE public.new_seq", "writer", false));

        var script = new SQLScript(new CoreSettings(), ";");
        oldColumn.appendAlterSQL(newColumn, script);

        assertEquals("""
                ALTER SEQUENCE public.old_seq RENAME TO new_seq;

                ALTER SEQUENCE public.new_seq OWNER TO new_owner;

                REVOKE USAGE ON SEQUENCE public.new_seq FROM reader;

                REVOKE ALL ON SEQUENCE public.new_seq FROM PUBLIC;

                REVOKE ALL ON SEQUENCE public.new_seq FROM new_owner;

                GRANT ALL ON SEQUENCE public.new_seq TO new_owner;

                GRANT SELECT ON SEQUENCE public.new_seq TO writer;""", script.getFullScript());
    }

    @Test
    void identitySequenceRenameRejectsPrivilegeThatCannotBeRetargeted() {
        PgColumn oldColumn = identityColumn("old_seq", "owner");
        oldColumn.getSequence().addPrivilege(new ExternalPrivilege());
        PgColumn newColumn = identityColumn("new_seq", "owner");

        var script = new SQLScript(new CoreSettings(), ";");
        var failure = assertThrows(IllegalStateException.class,
                () -> oldColumn.appendAlterSQL(newColumn, script));

        assertTrue(failure.getMessage().startsWith(
                "Privilege implementation cannot retarget a revoke after object rename:"),
                failure.getMessage());
    }

    @Test
    void privilegeOnlyChangeStaysBeforeExistingEndStatements() {
        PgSequence oldSequence = sequence("seq", "owner");
        PgSequence newSequence = sequence("seq", "owner");
        newSequence.addPrivilege(new PgPrivilege(
                "GRANT", "USAGE", "SEQUENCE public.seq", "reader", false));

        var script = new SQLScript(new CoreSettings(), ";");
        oldSequence.appendAlterSQL(newSequence, script);
        script.addStatement("END MARKER", SQLActionType.END);
        String sql = script.getFullScript();

        int grant = sql.indexOf("GRANT USAGE ON SEQUENCE public.seq TO reader;");
        int end = sql.indexOf("END MARKER;");
        assertAll(
                () -> assertTrue(grant >= 0 && grant < end, sql),
                () -> assertFalse(sql.contains("ALTER SEQUENCE public.seq OWNER TO"), sql));
    }

    private static PgSequence sequence(String name, String owner) {
        var schema = new PgSchema("public");
        var sequence = new PgSequence(name);
        sequence.setOwner(owner);
        schema.addChild(sequence);
        return sequence;
    }

    private static PgColumn identityColumn(String sequenceName, String sequenceOwner) {
        var schema = new PgSchema("public");
        var table = new PgSimpleTable("test_table");
        schema.addChild(table);

        var column = new PgColumn("id");
        column.setType("integer");
        column.setIdentityType("ALWAYS");
        table.addColumn(column);

        var sequence = new PgSequence(sequenceName);
        sequence.setOwner(sequenceOwner);
        sequence.setParent(schema);
        column.setSequence(sequence);
        return column;
    }

    private static final class ExternalPrivilege implements IPrivilege {

        @Override
        public boolean isRevoke() {
            return false;
        }

        @Override
        public String getCreationSQL() {
            return "GRANT USAGE ON SEQUENCE public.old_seq TO reader";
        }

        @Override
        public String getDropSQL() {
            return "REVOKE USAGE ON SEQUENCE public.old_seq FROM reader";
        }

        @Override
        public String getPermission() {
            return "USAGE";
        }

        @Override
        public String getRole() {
            return "reader";
        }

        @Override
        public String getName() {
            return "SEQUENCE public.old_seq";
        }

        @Override
        public void computeHash(Hasher hasher) {
            hasher.put(getCreationSQL());
        }
    }
}
