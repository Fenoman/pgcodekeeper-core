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
package org.pgcodekeeper.core.database.pg.jdbc;

import java.util.Objects;
import java.util.UUID;

/** Immutable identity and integrity metadata for one catalog pack generation. */
record PgCatalogReaderPackManifest(UUID generationId, long packSize,
        int rowCount, byte[] orderedFingerprint) {

    PgCatalogReaderPackManifest {
        Objects.requireNonNull(generationId, "generationId");
        Objects.requireNonNull(orderedFingerprint, "orderedFingerprint");
        if (packSize < 0 || rowCount < 0
                || orderedFingerprint.length != PgPackedCatalogHashes.MD5_BYTES) {
            throw new IllegalArgumentException("Invalid catalog pack manifest");
        }
        orderedFingerprint = orderedFingerprint.clone();
    }

    @Override
    public byte[] orderedFingerprint() {
        return orderedFingerprint.clone();
    }
}
