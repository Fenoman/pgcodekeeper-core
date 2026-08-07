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
package org.pgcodekeeper.core.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32C;

import org.pgcodekeeper.core.analysis.AnalysisReplayPayload.FileReferences;
import org.pgcodekeeper.core.analysis.AnalysisReplayPayload.StatementDependencies;
import org.pgcodekeeper.core.DangerStatement;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation.LocationType;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;

/**
 * The shipped codec, frozen at commit {@code c106361b}, kept as the other side
 * of the compatibility tests.
 * <p>
 * A user's analysis cache is written by one release and read by the next, so a
 * change to the encoder is only safe while the bytes stay the same. Comparing
 * the encoder against itself cannot show that; comparing it against the code
 * that wrote the caches already on disk can.
 * <p>
 * This is a verbatim copy of
 * {@code src/main/java/org/pgcodekeeper/core/analysis/AnalysisReplayCodec.java}
 * as of that commit - the only edits are the class name, its access and this
 * comment. <b>Do not refactor, tidy or re-indent it.</b> Its whole value is
 * being the previous release's code rather than a paraphrase of it. It is
 * replaced only when {@link AnalysisReplayCodec#FORMAT_VERSION} is deliberately
 * bumped, and then by a fresh copy of the release that is being left behind.
 * <p>
 * Original documentation follows.
 * <p>
 * Binary codec of a captured analysis result.
 * <p>
 * The container is a fixed header - magic, format version and the CRC32C of the
 * body - followed by one deflate-free varint body that starts with a string
 * dictionary. Every name, path, action, alias and enum constant is stored once
 * and referenced by id, which is what keeps a project of twenty thousand files
 * inside a few tens of megabytes.
 * <p>
 * Enum constants are written by <em>name</em>, never by ordinal: a payload
 * outlives the process that wrote it, and reordering a constant must not
 * silently retype half a million references.
 * <p>
 * Decoding is fail-closed and bounded. Every count is checked against a limit
 * before anything is allocated, every id against the dictionary size, and the
 * body against its checksum, so a damaged file raises
 * {@link AnalysisReplayFormatException} instead of producing a plausible but
 * wrong model.
 */
final class FrozenAnalysisReplayCodec {

    private static final byte[] MAGIC = "PGCKANL1".getBytes(StandardCharsets.US_ASCII);

    /**
     * Container revision. A reader refuses anything it was not written for.
     * <p>
     * Version 2 widened the captured walk to table columns. A version-1
     * container is missing every dependency edge a column default contributes,
     * and it cannot be told apart from a complete one by its own contents, so
     * the version is what rejects it.
     */
    public static final int FORMAT_VERSION = 2;

    /** Fixed header: magic, version, body length, body CRC32C. */
    public static final int HEADER_BYTES = 8 + 4 + 8 + 4;

    private static final long MAX_BODY_BYTES = 1L << 31;
    private static final int MAX_STRINGS = 1 << 24;
    private static final int MAX_STRING_BYTES = 1 << 20;
    private static final int MAX_ENTRIES = 1 << 26;
    private static final int MAX_ADDRESS_SEGMENTS = 64;

    private FrozenAnalysisReplayCodec() {
    }

    /**
     * Encodes a payload into its container form.
     *
     * @param payload captured analysis result
     * @param output  stream to write to, not closed by this method
     * @return number of bytes written
     * @throws IOException if writing fails
     */
    public static long write(AnalysisReplayPayload payload, OutputStream output)
            throws IOException {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(output, "output");
        var body = new BodyWriter();
        body.encode(payload);
        byte[] bytes = body.toByteArray();
        var crc = new CRC32C();
        crc.update(bytes, 0, bytes.length);

        var header = new byte[HEADER_BYTES];
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        putInt(header, 8, FORMAT_VERSION);
        putLong(header, 12, bytes.length);
        putInt(header, 20, (int) crc.getValue());
        output.write(header);
        output.write(bytes);
        return (long) HEADER_BYTES + bytes.length;
    }

    /**
     * Decodes a container written by {@link #write}.
     *
     * @param input stream positioned at the container start, not closed here
     * @return the decoded analysis result
     * @throws AnalysisReplayFormatException if the container is damaged or was
     *                                       written by another format version
     * @throws IOException                   if reading fails
     */
    public static AnalysisReplayPayload read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] header = input.readNBytes(HEADER_BYTES);
        if (header.length != HEADER_BYTES) {
            throw new AnalysisReplayFormatException(
                    "Analysis cache is shorter than its header");
        }
        if (!Arrays.equals(header, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw new AnalysisReplayFormatException("Analysis cache has a foreign magic");
        }
        int version = getInt(header, 8);
        if (version != FORMAT_VERSION) {
            throw new AnalysisReplayFormatException(
                    "Analysis cache format version " + version + " is not supported");
        }
        long length = getLong(header, 12);
        if (length < 0 || length > MAX_BODY_BYTES) {
            throw new AnalysisReplayFormatException(
                    "Analysis cache declares an unusable body length: " + length);
        }
        int expectedCrc = getInt(header, 20);
        byte[] body = input.readNBytes((int) length);
        if (body.length != length) {
            throw new AnalysisReplayFormatException("Analysis cache body is truncated");
        }
        var crc = new CRC32C();
        crc.update(body, 0, body.length);
        if ((int) crc.getValue() != expectedCrc) {
            throw new AnalysisReplayFormatException(
                    "Analysis cache body fails its checksum");
        }
        return new BodyReader(body).decode();
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static void putLong(byte[] target, int offset, long value) {
        putInt(target, offset, (int) (value >>> 32));
        putInt(target, offset + 4, (int) value);
    }

    private static int getInt(byte[] source, int offset) {
        return (source[offset] & 0xFF) << 24 | (source[offset + 1] & 0xFF) << 16
                | (source[offset + 2] & 0xFF) << 8 | source[offset + 3] & 0xFF;
    }

    private static long getLong(byte[] source, int offset) {
        return (long) getInt(source, offset) << 32 | getInt(source, offset + 4) & 0xFFFFFFFFL;
    }

    /**
     * Two-pass body encoder: the first pass interns every string of the
     * payload, the second writes ids only.
     */
    private static final class BodyWriter {

        private final Map<String, Integer> ids = new HashMap<>();
        private final List<String> strings = new ArrayList<>();
        private byte[] buffer = new byte[1 << 16];
        private int size;

        private void encode(AnalysisReplayPayload payload) throws IOException {
            intern(payload);
            writeVarInt(strings.size());
            for (String value : strings) {
                byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
                if (utf8.length > MAX_STRING_BYTES) {
                    throw new AnalysisReplayFormatException(
                            "Analysis cache string exceeds the encoding limit");
                }
                writeVarInt(utf8.length);
                write(utf8, utf8.length);
            }
            writeVarInt(payload.statementCount());

            writeVarInt(payload.dependencies().size());
            for (StatementDependencies entry : payload.dependencies()) {
                writeAddress(entry.address());
                writeVarInt(entry.references().size());
                for (ObjectReference reference : entry.references()) {
                    writeReference(reference);
                }
            }

            writeVarInt(payload.references().size());
            for (FileReferences file : payload.references()) {
                writeId(file.filePath());
                writeVarInt(file.locations().size());
                for (ObjectLocation location : file.locations()) {
                    writeLocation(location);
                }
            }

            writeVarInt(payload.suppressedRoutines().size());
            for (StatementAddress address : payload.suppressedRoutines()) {
                writeAddress(address);
            }
        }

        private void intern(AnalysisReplayPayload payload) {
            for (StatementDependencies entry : payload.dependencies()) {
                internAddress(entry.address());
                entry.references().forEach(this::internReference);
            }
            for (FileReferences file : payload.references()) {
                intern(file.filePath());
                for (ObjectLocation location : file.locations()) {
                    internReference(location.getObjectReference());
                    intern(location.getFilePath());
                    intern(location.getAction());
                    intern(location.getSql());
                    intern(location.getAlias());
                    intern(name(location.getLocationType()));
                    intern(name(location.getDanger()));
                }
            }
            payload.suppressedRoutines().forEach(this::internAddress);
        }

        private void internAddress(StatementAddress address) {
            for (StatementAddress.Segment segment : address.segments()) {
                intern(segment.type().name());
                intern(segment.name());
            }
        }

        private void internReference(ObjectReference reference) {
            if (reference == null) {
                return;
            }
            intern(reference.schema());
            intern(reference.table());
            intern(reference.column());
            intern(name(reference.type()));
        }

        private static String name(Enum<?> value) {
            return value == null ? null : value.name();
        }

        private void intern(String value) {
            if (value == null) {
                return;
            }
            if (ids.putIfAbsent(value, strings.size() + 1) == null) {
                strings.add(value);
            }
        }

        private void writeAddress(StatementAddress address) throws IOException {
            List<StatementAddress.Segment> segments = address.segments();
            if (segments.size() > MAX_ADDRESS_SEGMENTS) {
                throw new AnalysisReplayFormatException(
                        "Analysis cache statement address is too deep");
            }
            writeVarInt(segments.size());
            for (StatementAddress.Segment segment : segments) {
                writeId(segment.type().name());
                writeId(segment.name());
            }
        }

        private void writeReference(ObjectReference reference) {
            if (reference == null) {
                writeByte(0);
                return;
            }
            writeByte(1);
            writeId(reference.schema());
            writeId(reference.table());
            writeId(reference.column());
            writeId(name(reference.type()));
        }

        private void writeLocation(ObjectLocation location) {
            // The location carries its own file path and that path is part of
            // its identity, so it must be stored even though it is almost
            // always the key of the entry that holds it.
            writeId(location.getFilePath());
            writeSignedVarInt(location.getOffset());
            writeSignedVarInt(location.getLineNumber());
            writeSignedVarInt(location.getCharPositionInLine());
            writeSignedVarInt(location.getObjLength());
            writeId(location.getAction());
            writeId(location.getSql());
            writeId(location.getAlias());
            writeId(name(location.getLocationType()));
            writeId(name(location.getDanger()));
            writeReference(location.getObjectReference());
        }

        private void writeId(String value) {
            writeVarInt(value == null ? 0 : ids.get(value));
        }

        private void writeByte(int value) {
            ensure(1);
            buffer[size++] = (byte) value;
        }

        private void writeVarInt(int value) {
            ensure(5);
            int remaining = value;
            while ((remaining & ~0x7F) != 0) {
                buffer[size++] = (byte) (remaining & 0x7F | 0x80);
                remaining >>>= 7;
            }
            buffer[size++] = (byte) remaining;
        }

        private void writeSignedVarInt(int value) {
            writeVarInt(value << 1 ^ value >> 31);
        }

        private void write(byte[] bytes, int length) {
            ensure(length);
            System.arraycopy(bytes, 0, buffer, size, length);
            size += length;
        }

        private void ensure(int extra) {
            if (size + extra <= buffer.length) {
                return;
            }
            long required = (long) size + extra;
            long capacity = Math.max(required, Math.min(MAX_BODY_BYTES, buffer.length * 2L));
            if (capacity > MAX_BODY_BYTES) {
                throw new IllegalStateException("Analysis cache body exceeds its limit");
            }
            buffer = Arrays.copyOf(buffer, (int) capacity);
        }

        private byte[] toByteArray() {
            return Arrays.copyOf(buffer, size);
        }
    }

    /** Bounded body decoder. */
    private static final class BodyReader {

        private final byte[] body;
        private int position;
        private String[] strings;

        private BodyReader(byte[] body) {
            this.body = body;
        }

        private AnalysisReplayPayload decode() throws AnalysisReplayFormatException {
            int stringCount = readCount(MAX_STRINGS, "strings");
            strings = new String[stringCount];
            for (int i = 0; i < stringCount; i++) {
                int length = readCount(MAX_STRING_BYTES, "string length");
                require(length);
                strings[i] = new String(body, position, length, StandardCharsets.UTF_8);
                position += length;
            }
            // A plain magnitude, not a container count: nothing follows it
            // per unit, so the remaining-body bound of readCount would reject
            // a small payload that happens to describe a large model.
            int statementCount = readVarInt();
            if (statementCount > MAX_ENTRIES) {
                throw new AnalysisReplayFormatException(
                        "Analysis cache declares too many statements: "
                                + statementCount);
            }

            int dependencyCount = readCount(MAX_ENTRIES, "dependency entries");
            var dependencies = new ArrayList<StatementDependencies>(dependencyCount);
            for (int i = 0; i < dependencyCount; i++) {
                StatementAddress address = readAddress();
                int count = readCount(MAX_ENTRIES, "dependencies");
                var references = new ArrayList<ObjectReference>(count);
                for (int j = 0; j < count; j++) {
                    ObjectReference reference = readReference();
                    if (reference == null) {
                        throw new AnalysisReplayFormatException(
                                "Analysis cache has a null dependency edge");
                    }
                    references.add(reference);
                }
                dependencies.add(new StatementDependencies(address, references));
            }

            int fileCount = readCount(MAX_ENTRIES, "reference files");
            var references = new ArrayList<FileReferences>(fileCount);
            for (int i = 0; i < fileCount; i++) {
                String filePath = readRequiredString("reference file path");
                int count = readCount(MAX_ENTRIES, "reference locations");
                var locations = new ArrayList<ObjectLocation>(count);
                for (int j = 0; j < count; j++) {
                    locations.add(readLocation());
                }
                references.add(new FileReferences(filePath, locations));
            }

            int suppressedCount = readCount(MAX_ENTRIES, "suppressed routines");
            var suppressed = new ArrayList<StatementAddress>(suppressedCount);
            for (int i = 0; i < suppressedCount; i++) {
                suppressed.add(readAddress());
            }
            if (position != body.length) {
                throw new AnalysisReplayFormatException(
                        "Analysis cache body has trailing bytes");
            }
            return new AnalysisReplayPayload(
                    dependencies, references, suppressed, statementCount);
        }

        private StatementAddress readAddress() throws AnalysisReplayFormatException {
            int count = readCount(MAX_ADDRESS_SEGMENTS, "address segments");
            if (count == 0) {
                throw new AnalysisReplayFormatException(
                        "Analysis cache has an empty statement address");
            }
            var segments = new ArrayList<StatementAddress.Segment>(count);
            for (int i = 0; i < count; i++) {
                DbObjType type = readEnum(DbObjType.class, "object type");
                if (type == null) {
                    throw new AnalysisReplayFormatException(
                            "Analysis cache address segment has no type");
                }
                segments.add(new StatementAddress.Segment(
                        type, readRequiredString("address segment name")));
            }
            return new StatementAddress(segments);
        }

        private ObjectReference readReference() throws AnalysisReplayFormatException {
            if (readByte() == 0) {
                return null;
            }
            String schema = readString();
            String table = readString();
            String column = readString();
            DbObjType type = readEnum(DbObjType.class, "reference type");
            return new ObjectReference(schema, table, column, type);
        }

        private ObjectLocation readLocation() throws AnalysisReplayFormatException {
            var builder = new ObjectLocation.Builder()
                    .setFilePath(readString())
                    .setOffset(readSignedVarInt())
                    .setLineNumber(readSignedVarInt())
                    .setCharPositionInLine(readSignedVarInt())
                    .setLength(readSignedVarInt())
                    .setAction(readString())
                    .setSql(readString())
                    .setAlias(readString());
            LocationType locationType = readEnum(LocationType.class, "location type");
            DangerStatement danger = readEnum(DangerStatement.class, "danger statement");
            builder.setLocationType(locationType);
            builder.setReference(readReference());
            ObjectLocation location = builder.build();
            if (danger != null) {
                location.setWarning(danger);
            }
            return location;
        }

        private <E extends Enum<E>> E readEnum(Class<E> type, String label)
                throws AnalysisReplayFormatException {
            String name = readString();
            if (name == null) {
                return null;
            }
            try {
                return Enum.valueOf(type, name);
            } catch (IllegalArgumentException ex) {
                throw new AnalysisReplayFormatException(
                        "Analysis cache has an unknown " + label + ": " + name, ex);
            }
        }

        private String readRequiredString(String label) throws AnalysisReplayFormatException {
            String value = readString();
            if (value == null) {
                throw new AnalysisReplayFormatException(
                        "Analysis cache has a null " + label);
            }
            return value;
        }

        private String readString() throws AnalysisReplayFormatException {
            int id = readVarInt();
            if (id == 0) {
                return null;
            }
            if (id > strings.length) {
                throw new AnalysisReplayFormatException(
                        "Analysis cache references string " + id + " of " + strings.length);
            }
            return strings[id - 1];
        }

        private int readCount(int maximum, String label) throws AnalysisReplayFormatException {
            int count = readVarInt();
            if (count > maximum) {
                throw new AnalysisReplayFormatException(
                        "Analysis cache " + label + " count exceeds the limit: " + count);
            }
            // A count can never need more body than one byte per element.
            if (count > body.length - position) {
                throw new AnalysisReplayFormatException(
                        "Analysis cache " + label + " count exceeds the remaining body");
            }
            return count;
        }

        private int readByte() throws AnalysisReplayFormatException {
            require(1);
            return body[position++] & 0xFF;
        }

        private int readVarInt() throws AnalysisReplayFormatException {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int current = readByte();
                if (shift == 28 && (current & 0xF0) != 0) {
                    throw new AnalysisReplayFormatException(
                            "Analysis cache contains an overflowing varint");
                }
                value |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    if (shift != 0 && (current & 0x7F) == 0) {
                        throw new AnalysisReplayFormatException(
                                "Analysis cache contains a non-minimal varint");
                    }
                    return value;
                }
            }
            throw new AnalysisReplayFormatException(
                    "Analysis cache contains an unterminated varint");
        }

        private int readSignedVarInt() throws AnalysisReplayFormatException {
            int value = readVarInt();
            return value >>> 1 ^ -(value & 1);
        }

        private void require(int bytes) throws AnalysisReplayFormatException {
            if (bytes < 0 || bytes > body.length - position) {
                throw new AnalysisReplayFormatException("Analysis cache body is truncated");
            }
        }
    }
}
