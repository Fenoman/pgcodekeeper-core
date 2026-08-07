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
package org.pgcodekeeper.core.monitor;

import java.io.Serial;
import java.util.Objects;

/**
 * Internal transport used to distinguish a monitor implementation failure
 * from a genuine parser or analysis failure while it crosses shared parser
 * error-handling boundaries. This exception must be unwrapped before reaching
 * a caller-facing API.
 */
public final class MonitorInvocationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 7316370365797279588L;

    private final RuntimeException failure;

    public MonitorInvocationException(RuntimeException failure) {
        super(null, null, false, false);
        this.failure = Objects.requireNonNull(failure);
    }

    public RuntimeException getFailure() {
        return failure;
    }
}
