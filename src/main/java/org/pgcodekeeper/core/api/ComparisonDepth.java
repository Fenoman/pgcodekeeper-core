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
package org.pgcodekeeper.core.api;

import org.pgcodekeeper.core.settings.ISettings;

/**
 * How deep a comparison loads its two sides.
 *
 * <p>This is a property of one operation and never of the settings. A caller
 * that will build a migration script asks for {@link #FULL} in code, so no
 * configuration mistake can silently downgrade a script-bound load into a
 * structural one.</p>
 *
 * <p>Depth answers exactly one question - did the analysis phase run - and
 * that is a weaker claim than "this model is ready to be scripted". What the
 * analysis phase covers once it runs is still the settings' business: with
 * {@link ISettings#isPgRoutineBodySkipMatchedAnalysis()} on, which is the
 * default, a routine body that is byte-identical on both sides is never
 * parsed, so it contributes no dependencies even under {@link #FULL}, and a
 * drop-cascade script may then omit collateral recreates of such unchanged
 * routines. A caller who needs every dependency resolved has to ask that
 * setting too; depth alone will not tell it. Deliberately, the core still
 * builds the script either way - the CLI ships that skip on by default and
 * production pipelines depend on it - so refusing here would break them
 * rather than protect them.</p>
 */
public enum ComparisonDepth {

    /**
     * Structure and analysis: the analysis phase ran on both sides. Necessary
     * for a migration script, and on its own not sufficient - see the class
     * javadoc for what the settings may still leave unanalyzed.
     */
    FULL,

    /**
     * Structure alone. Every definition is present and comparable - a diff tree
     * built from this model equals the one built from a full load - but nothing
     * carries dependencies, so no script may be built from it.
     */
    STRUCTURAL_ONLY
}
