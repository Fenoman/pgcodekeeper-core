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
package org.pgcodekeeper.core.database.pg.parser.statement;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgDomain;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * The {@code NOT NULL} of a domain, and the {@code ALTER DOMAIN} that states
 * it.
 * <p>
 * A separate class from {@code PgTypesReaderDomainTest} on purpose: that one
 * speaks for the domain's two expression-shaped members and the normalization
 * contract they carry, and a flag has no normalized half to contract about.
 * What it shares with the {@code DEFAULT} is the writer -
 * {@link PgAlterDomain#parseObject()} - and the failure mode: an alternative
 * the grammar accepts but no writer reads does not merely fail to improve the
 * model, it inverts it. The flag arrives absent, the comparison calls the
 * domain changed, and {@code PgDomain.appendAlterSQL:149-157} writes the
 * <i>opposite</i> statement against the database.
 * <p>
 * Everything here runs the whole project-file route through
 * {@link PgDumpLoader} and then the whole diff pipeline, because that is where
 * the inversion becomes visible: it is a property of the model reaching the
 * script generator, not of any one parse.
 */
class PgAlterDomainNotNullTest {

    private static final String SCHEMA_NAME = "public";
    private static final String DOMAIN_NAME = "positive_amount";
    private static final String DOMAIN_TYPE = "integer";

    /**
     * The defect, stated directly: a project file that declares {@code NOT
     * NULL} through an {@code ALTER} against a database that already has it
     * must produce no script at all.
     * <p>
     * With the clause unread the tool emitted {@code ALTER DOMAIN ... DROP NOT
     * NULL} - it removed from the database the very constraint the project file
     * declares.
     */
    @Test
    void aNotNullDeclaredByAnAlterIsNotDroppedFromTheDatabase() throws Exception {
        String script = pipeline(createDomain(true), setNotNullByAlter());
        assertEquals("", script.trim(),
                () -> "a NOT NULL declared by ALTER must read as the one the database holds, got:\n" + script);
    }

    /**
     * The mirror, and the reason {@code DROP NOT NULL} may not simply be
     * ignored: a project file states the shape the domain is in, not the
     * history of how it got there, so a file whose last word on the flag is
     * {@code DROP} describes a domain without it, and a database that still has
     * it differs from that file.
     * <p>
     * The file declares {@code NOT NULL} on the {@code CREATE} and drops it
     * afterwards, so an inert branch cannot pass this: a file that never
     * declared the flag would compare as changed whether the {@code ALTER} was
     * read or not.
     */
    @Test
    void aDropNotNullInAProjectFileClearsTheFlag() throws Exception {
        String script = pipeline(createDomain(true), dropNotNullByAlter());
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER DOMAIN %s.%s
                \tDROP NOT NULL;""".formatted(SCHEMA_NAME, DOMAIN_NAME), script.trim());
    }

    /**
     * The same two statements read as state rather than as a script: the model
     * a file builds through an {@code ALTER} is the model the equivalent
     * {@code CREATE} builds, and writes itself back out the same way.
     * <p>
     * The flag has no getter, so it is observed the way the rest of the tree
     * observes such a field - through the DDL the domain writes and through the
     * comparison. Both loaders parent their domain under a {@code public}
     * schema of their own database, so the two sides hash over the same chain
     * of names ({@code AbstractStatement.computeNamesHash}) and a mismatch here
     * is about the flag rather than about the parenting.
     */
    @Test
    void bothAlterationsLeaveTheModelTheCreateWouldHaveBuilt() throws Exception {
        assertEquals(creationScript(domainOf(createDomain(true))),
                creationScript(domainOf(setNotNullByAlter())),
                "SET NOT NULL must build the domain the equivalent CREATE builds");
        assertEquals(creationScript(domainOf(createDomain(false))),
                creationScript(domainOf(dropNotNullByAlter())),
                "and DROP NOT NULL the one without the flag");

        assertTrue(domainOf(createDomain(true)).compare(domainOf(setNotNullByAlter())),
                "the two routes to a NOT NULL domain must compare as one object");
        assertEquals(domainOf(createDomain(true)).hashCode(), domainOf(setNotNullByAlter()).hashCode(),
                "and hash the same");
        assertTrue(domainOf(createDomain(false)).compare(domainOf(dropNotNullByAlter())),
                "same for the two routes to a domain without it");
        assertEquals(domainOf(createDomain(false)).hashCode(), domainOf(dropNotNullByAlter()).hashCode(),
                "and the same hash");
    }

    private static PgDatabase createDomain(boolean notNull) throws Exception {
        return loadProjectFile("CREATE DOMAIN %s.%s AS %s%s;"
                .formatted(SCHEMA_NAME, DOMAIN_NAME, DOMAIN_TYPE, notNull ? " NOT NULL" : ""));
    }

    private static PgDatabase setNotNullByAlter() throws Exception {
        return loadProjectFile("""
                CREATE DOMAIN %1$s.%2$s AS %3$s;
                ALTER DOMAIN %1$s.%2$s SET NOT NULL;
                """.formatted(SCHEMA_NAME, DOMAIN_NAME, DOMAIN_TYPE));
    }

    private static PgDatabase dropNotNullByAlter() throws Exception {
        return loadProjectFile("""
                CREATE DOMAIN %1$s.%2$s AS %3$s NOT NULL;
                ALTER DOMAIN %1$s.%2$s DROP NOT NULL;
                """.formatted(SCHEMA_NAME, DOMAIN_NAME, DOMAIN_TYPE));
    }

    private static PgDatabase loadProjectFile(String sql) throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "domain not null test", new CoreSettings()).load();
    }

    private static PgDomain domainOf(PgDatabase db) {
        return db.getChildren()
                .filter(PgSchema.class::isInstance)
                .map(PgSchema.class::cast)
                .map(s -> s.getDomain(DOMAIN_NAME))
                .filter(d -> d != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no domain was parsed"));
    }

    private static String creationScript(PgDomain domain) {
        var settings = new CoreSettings();
        var script = new SQLScript(settings, domain.getSeparator());
        domain.getCreationSQL(script);
        return script.getFullScript();
    }

    private static String pipeline(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());
    }
}
