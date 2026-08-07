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
package org.pgcodekeeper.core.database.pg.loader;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.loader.ComparisonExtensionContext;
import org.pgcodekeeper.core.settings.CoreSettings;

class PgProjectLoaderComparisonExtensionTest {

    @Test
    void topLevelProjectLoaderAlwaysRegistersTheAllocationOnlyProducerMarker()
            throws Exception {
        ComparisonExtensionContext context = mock(ComparisonExtensionContext.class);
        new PgProjectLoader(Path.of("project"), new CoreSettings())
                .registerComparisonExtensions(context);

        verify(context).register(
                eq(PgRoutineBodyComparisonExtension.KEY),
                org.mockito.ArgumentMatchers.any(
                        PgRoutineBodyComparisonExtension.ProjectEndpoint.class));
    }
}
