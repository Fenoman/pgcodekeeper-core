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

import java.util.concurrent.ExecutorService;

/**
 * Optional package-local seam for execution-policy verification. Normal
 * production runs have no observer installed.
 */
interface ParserExecutionObserver {

    ExecutorService observeExecutor(ExecutorService executor);

    void scopeCreated(ExecutorService executor);

    void queueCreated(boolean root);

    void admissionWaitStarted();
}
