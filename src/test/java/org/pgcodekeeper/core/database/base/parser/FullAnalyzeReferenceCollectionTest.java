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
package org.pgcodekeeper.core.database.base.parser;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.antlr.v4.runtime.ParserRuleContext;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.api.schema.meta.IMetaContainer;
import org.pgcodekeeper.core.database.base.parser.launcher.AbstractAnalysisLauncher;
import org.pgcodekeeper.core.database.base.schema.meta.MetaContainer;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgFunction;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.monitor.NullMonitor;

/**
 * A database that does not index object references turns every
 * {@code addReference} into a no-op, so the offset-corrected copies the
 * launchers build for that index are allocated, held for the whole analysis and
 * dropped unread. This is the CLI diff path.
 */
class FullAnalyzeReferenceCollectionTest {

    /**
     * Records one reference-shaped location, exactly as a real launcher would
     * hand it to {@code AbstractAnalysisLauncher}, which then copies it into
     * its reference list.
     */
    private static final class RecordingLauncher extends AbstractAnalysisLauncher {

        RecordingLauncher(PgFunction function) {
            super(function, new ParserRuleContext(), "reference_collection.sql");
        }

        @Override
        protected Set<ObjectLocation> analyze(ParserRuleContext ignored, IMetaContainer meta) {
            return Set.of(new ObjectLocation.Builder()
                    .setFilePath("reference_collection.sql")
                    .setReference(new ObjectReference("public", "referenced_table", DbObjType.TABLE))
                    .setOffset(10)
                    // a location on line 0 is never copied at all, so the case
                    // this test is about needs a real line
                    .setLineNumber(3)
                    .setCharPositionInLine(5)
                    .setLocationType(ObjectLocation.LocationType.REFERENCE)
                    .build());
        }
    }

    private static RecordingLauncher analyzeWith(PgDatabase db) throws Exception {
        var schema = new PgSchema("public");
        var function = new PgFunction("collects_a_reference");
        db.addChild(schema);
        schema.addChild(function);
        var launcher = new RecordingLauncher(function);
        db.addAnalysisLauncher(launcher);

        List<Object> errors = new ArrayList<>();
        FullAnalyze.fullAnalyze(db, new MetaContainer(), errors, new NullMonitor());
        assertTrue(errors.isEmpty(), errors::toString);
        return launcher;
    }

    @Test
    void databaseThatIndexesReferencesStillGetsThem() throws Exception {
        var launcher = analyzeWith(new PgDatabase());

        assertEquals(1, launcher.getReferences().size(),
                "an indexing database must keep receiving the analysis references");
    }

    @Test
    void databaseThatDropsReferencesNeverBuildsThem() throws Exception {
        var db = new PgDatabase(false);

        var launcher = analyzeWith(db);

        assertAll(
                () -> assertTrue(launcher.getReferences().isEmpty(),
                        "the reverse index is off: these copies would be dropped unread"),
                () -> assertTrue(db.getObjReferences().isEmpty(),
                        "a non-indexing database must stay empty"));
    }
}
