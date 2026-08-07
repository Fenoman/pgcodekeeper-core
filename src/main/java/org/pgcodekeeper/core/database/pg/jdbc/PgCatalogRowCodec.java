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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.zip.CRC32;

/**
 * Legacy self-checking codec for individual PostgreSQL catalog cache rows.
 * Labels and values remain interleaved in the version 1 wire format, while
 * value encoding is shared with reader packs through
 * {@link PgCatalogRowValueCodec}.
 */
public final class PgCatalogRowCodec {

    /** Bump on any change of the legacy payload layout or capture semantics. */
    public static final byte FORMAT_VERSION = 1;

    private static final int MIN_COLUMN_BYTES = Integer.BYTES + Byte.BYTES;

    private PgCatalogRowCodec() {
        // only statics
    }

    /** Signals a row value the codec has no stable representation for. */
    public static final class UnsupportedRowValueException extends Exception {

        private static final long serialVersionUID = 1L;

        UnsupportedRowValueException(String message) {
            super(message);
        }
    }

    /**
     * Serializes a row using the unchanged version 1 labels-and-values wire
     * format. Oversized rows return {@code null} before allocating a payload.
     */
    public static byte[] serialize(PgCachedCatalogRow row, int maxPayloadBytes)
            throws UnsupportedRowValueException {
        requirePayloadLimit(maxPayloadBytes);
        String[] labels = row.labels();
        Object[] values = row.values();

        long payloadSize = Byte.BYTES + Integer.BYTES + Integer.BYTES;
        if (payloadSize > maxPayloadBytes) {
            return null;
        }
        for (int i = 0; i < labels.length; i++) {
            payloadSize = addBounded(payloadSize,
                    PgCatalogRowValueCodec.encodedStringSize(labels[i]),
                    maxPayloadBytes);
            if (payloadSize > maxPayloadBytes) {
                return null;
            }
            payloadSize = addBounded(payloadSize,
                    PgCatalogRowValueCodec.encodedValueSize(
                            values[i], maxPayloadBytes - payloadSize),
                    maxPayloadBytes);
            if (payloadSize > maxPayloadBytes) {
                return null;
            }
        }

        byte[] payload = new byte[(int) payloadSize];
        var output = new PgCatalogRowValueCodec.ArrayDataOutput(payload);
        try {
            output.writeByte(FORMAT_VERSION);
            output.writeInt(labels.length);
            for (int i = 0; i < labels.length; i++) {
                PgCatalogRowValueCodec.writeString(labels[i], output);
                PgCatalogRowValueCodec.writeValue(values[i], output);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unexpected in-memory catalog row write failure", ex);
        }

        int bodyLength = output.position();
        var crc = new CRC32();
        crc.update(payload, 0, bodyLength);
        try {
            output.writeInt((int) crc.getValue());
        } catch (IOException ex) {
            throw new IllegalStateException("Unexpected in-memory catalog row write failure", ex);
        }
        if (output.position() != payload.length) {
            throw new IllegalStateException("Catalog row size calculation mismatch");
        }
        return payload;
    }

    /**
     * Deserializes a legacy payload, returning {@code null} for every version,
     * checksum, bounds, truncation or trailing-data mismatch.
     */
    public static PgCachedCatalogRow deserialize(byte[] payload) {
        if (payload == null || payload.length < 1 + Integer.BYTES + Integer.BYTES) {
            return null;
        }
        int bodyLength = payload.length - Integer.BYTES;
        var crc = new CRC32();
        crc.update(payload, 0, bodyLength);
        int expected = ((payload[bodyLength] & 0xFF) << 24)
                | ((payload[bodyLength + 1] & 0xFF) << 16)
                | ((payload[bodyLength + 2] & 0xFF) << 8)
                | (payload[bodyLength + 3] & 0xFF);
        if ((int) crc.getValue() != expected) {
            return null;
        }

        var data = new DataInputStream(new ByteArrayInputStream(payload, 0, bodyLength));
        var input = new PgCatalogRowValueCodec.BoundedDataInput(data, bodyLength);
        try {
            if (input.readByte() != FORMAT_VERSION) {
                return null;
            }
            int columnCount = input.readInt();
            if (columnCount < 0 || columnCount > Short.MAX_VALUE
                    || columnCount > input.remaining() / MIN_COLUMN_BYTES) {
                return null;
            }
            var labels = new String[columnCount];
            var values = new Object[columnCount];
            for (int i = 0; i < columnCount; i++) {
                labels[i] = PgCatalogRowValueCodec.readString(input);
                values[i] = PgCatalogRowValueCodec.readValue(input);
            }
            if (input.remaining() != 0) {
                return null;
            }
            return new PgCachedCatalogRow(labels, values);
        } catch (IOException | IllegalArgumentException ex) {
            return null;
        }
    }

    private static long addBounded(long current, long addition, long limit) {
        if (addition < 0 || current > limit || addition > limit - current) {
            return limit + 1L;
        }
        return current + addition;
    }

    private static void requirePayloadLimit(int maxPayloadBytes) {
        if (maxPayloadBytes <= 0 || maxPayloadBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Row payload cap must be positive and smaller than Integer.MAX_VALUE");
        }
    }
}
