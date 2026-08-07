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
package org.pgcodekeeper.core.database.pg.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * A primary key adopting an existing index is legal DDL that a project file may
 * hold. The grammar has always accepted it; the listener used to dereference the
 * tablespace branch for it and lose the whole statement to a swallowed NPE.
 */
class PgPrimaryKeyUsingIndexTest {

    private final PgDatabaseProvider databaseProvider = new PgDatabaseProvider();

    private static final String USING_NAMED_INDEX = """
            CREATE TABLE public.t1 (id bigint NOT NULL);
            CREATE UNIQUE INDEX t1_uq ON public.t1 USING btree (id);
            ALTER TABLE public.t1 ADD CONSTRAINT t1_pkey PRIMARY KEY USING INDEX t1_uq;
            """;

    private static final String USING_TABLESPACE = """
            CREATE TABLE public.t2 (id bigint NOT NULL);
            ALTER TABLE public.t2 ADD CONSTRAINT t2_pkey PRIMARY KEY (id) USING INDEX TABLESPACE fast_ts;
            """;

    @Test
    void aPrimaryKeyAdoptingAnIndexSurvivesTheParse() throws IOException, InterruptedException {
        IDatabase db = load(USING_NAMED_INDEX);
        Assertions.assertNotNull(
                db.getStatement(new ObjectReference("public", "t1", DbObjType.TABLE)),
                "the table must survive a constraint the parser used to choke on");
        Assertions.assertNotNull(
                db.getStatement(new ObjectReference("public", "t1", "t1_pkey", DbObjType.CONSTRAINT)),
                "the primary key itself must reach the model");
    }

    @Test
    void aPrimaryKeyWithATablespaceStillKeepsIt() throws IOException, InterruptedException {
        IDatabase db = load(USING_TABLESPACE);
        Assertions.assertNotNull(
                db.getStatement(new ObjectReference("public", "t2", "t2_pkey", DbObjType.CONSTRAINT)),
                "the tablespace branch must keep working");
    }

    @Test
    void aPrimaryKeyAdoptingAnIndexRegeneratesAsValidSql() throws IOException, InterruptedException {
        IDatabase db = load(USING_NAMED_INDEX);
        IStatement constraint = db.getStatement(
                new ObjectReference("public", "t1", "t1_pkey", DbObjType.CONSTRAINT));
        CoreSettings settings = new CoreSettings();
        var script = new SQLScript(settings, constraint.getSeparator());

        constraint.getCreationSQL(script);

        // No column list is known here - fillConstrPk() deliberately does not
        // resolve the columns of an adopted index - but the adopted index's own
        // name is, so the statement regenerates as "PRIMARY KEY USING INDEX
        // t1_uq" instead of a bare "PRIMARY KEY" with neither a column list nor
        // an adopted index, which PostgreSQL rejects as a syntax error.
        Assertions.assertEquals(
                "ALTER TABLE public.t1\n\tADD CONSTRAINT t1_pkey PRIMARY KEY USING INDEX t1_uq;",
                script.getFullScript());
    }

    private IDatabase load(String sql) throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        var loader = databaseProvider.getDumpLoader(
                () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)),
                "test", settings);
        IDatabase db = loader.load();
        Assertions.assertTrue(settings.getErrors().isEmpty(),
                () -> "the parse must report no errors, got: " + settings.getErrors());
        return db;
    }
}
