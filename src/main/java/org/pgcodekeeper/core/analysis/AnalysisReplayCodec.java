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
import java.util.function.IntFunction;
import java.util.zip.CRC32C;

import org.pgcodekeeper.core.analysis.AnalysisReplayPayload.FileReferences;
import org.pgcodekeeper.core.analysis.AnalysisReplayPayload.StatementDependencies;
import org.pgcodekeeper.core.DangerStatement;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation.LocationType;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;

/**
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
 * Both directions hold the body in fixed blocks and join it nowhere. Encoding
 * peaks at the body and one block over it; decoding lets each block go as it
 * passes it, so the body has drained away by the time the payload it becomes is
 * at its largest. It matters because both run inside an IDE whose heap is
 * already holding, or is about to hold, the model the body describes.
 * <p>
 * Decoding is fail-closed and bounded. Every count is checked against a limit
 * before anything is allocated, every id against the dictionary size, and the
 * body against its checksum <em>before</em> a single byte of it is interpreted,
 * so a damaged file raises {@link AnalysisReplayFormatException} instead of
 * producing a plausible but wrong model.
 */
public final class AnalysisReplayCodec {

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

    /**
     * Size of one body block, used by both directions.
     * <p>
     * Blocks are never copied and never outgrown, so a small block costs
     * nothing but one more list entry - a seventy-megabyte body is under three
     * hundred of them - while a large one risks being allocated away from the
     * young generation: G1 calls an object humongous once it reaches half a
     * region, and the smallest region it ever uses is one megabyte. A quarter
     * of a megabyte stays clear of that threshold at any heap size.
     */
    static final int BLOCK_BYTES = 1 << 18;

    private static final long MAX_BODY_BYTES = 1L << 31;
    private static final int MAX_STRINGS = 1 << 24;
    private static final int MAX_STRING_BYTES = 1 << 20;
    private static final int MAX_ENTRIES = 1 << 26;
    private static final int MAX_ADDRESS_SEGMENTS = 64;

    private AnalysisReplayCodec() {
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
        return write(payload, output, byte[]::new);
    }

    /**
     * Encodes a payload taking every byte of the body from one allocator.
     * <p>
     * The encoder holds the body nowhere else, so this seam shows a test the
     * whole memory shape of an encoding: how many blocks were taken and how
     * large each one was.
     *
     * @param payload   captured analysis result
     * @param output    stream to write to, not closed by this method
     * @param allocator source of body blocks
     * @return number of bytes written
     * @throws IOException if writing fails
     */
    static long write(AnalysisReplayPayload payload, OutputStream output,
            IntFunction<byte[]> allocator) throws IOException {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(allocator, "allocator");
        var writer = new BodyWriter(allocator);
        writer.encode(payload);
        BlockBody body = writer.seal();

        var header = new byte[HEADER_BYTES];
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        putInt(header, 8, FORMAT_VERSION);
        putLong(header, 12, body.length());
        putInt(header, 20, body.checksum());
        output.write(header);
        body.writeTo(output);
        return HEADER_BYTES + body.length();
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
        return read(input, byte[]::new);
    }

    /**
     * Decodes a container taking every byte of the body from one allocator.
     * <p>
     * The decoder holds the body nowhere else, so this seam shows a test the
     * whole memory shape of a decoding: how many blocks were taken and how
     * large each one was.
     *
     * @param input     stream positioned at the container start, not closed here
     * @param allocator source of body blocks
     * @return the decoded analysis result
     * @throws AnalysisReplayFormatException if the container is damaged or was
     *                                       written by another format version
     * @throws IOException                   if reading fails
     */
    static AnalysisReplayPayload read(InputStream input, IntFunction<byte[]> allocator)
            throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(allocator, "allocator");
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
        // The body is walked twice, and was before this too: once to prove it
        // whole and once to interpret it. Both walks are over memory the single
        // pass over the stream has already filled, so the file is still read
        // once, and the order that matters - integrity first, meaning second -
        // is the order that is kept.
        var body = BlockBody.readFrom(input, length, allocator);
        if (body.checksum() != expectedCrc) {
            throw new AnalysisReplayFormatException(
                    "Analysis cache body fails its checksum");
        }
        return new BodyReader(body, allocator).decode();
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
     * <p>
     * The body lives in a list of {@link #BLOCK_BYTES} blocks rather than in
     * one array that doubles when outgrown. Growing by doubling made the
     * encoder hold the old array, its twice-larger replacement and, once the
     * body was complete, an exact-sized copy of it - close to three times the
     * body for a heap that was asked for one. A block is filled once and never
     * moved: the length is the sum of the blocks, the checksum is taken over
     * them in order and the stream is fed the same way, so nothing is ever
     * copied and nothing is joined.
     */
    private static final class BodyWriter {

        private final Map<String, Integer> ids = new HashMap<>();
        private final List<String> strings = new ArrayList<>();
        private final IntFunction<byte[]> allocator;
        private final List<byte[]> blocks = new ArrayList<>();
        private byte[] block;
        private int offset;

        private BodyWriter(IntFunction<byte[]> allocator) {
            this.allocator = allocator;
            addBlock();
        }

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
            reserve(1);
            put(value);
        }

        private void writeVarInt(int value) {
            reserve(5);
            int remaining = value;
            while ((remaining & ~0x7F) != 0) {
                put(remaining & 0x7F | 0x80);
                remaining >>>= 7;
            }
            put(remaining);
        }

        private void writeSignedVarInt(int value) {
            writeVarInt(value << 1 ^ value >> 31);
        }

        /** Appends bytes, spilling into further blocks as they fill up. */
        private void write(byte[] bytes, int length) {
            reserve(length);
            int written = 0;
            while (written < length) {
                if (offset == BLOCK_BYTES) {
                    addBlock();
                }
                int chunk = Math.min(length - written, BLOCK_BYTES - offset);
                System.arraycopy(bytes, written, block, offset, chunk);
                offset += chunk;
                written += chunk;
            }
        }

        private void put(int value) {
            if (offset == BLOCK_BYTES) {
                addBlock();
            }
            block[offset++] = (byte) value;
        }

        private void addBlock() {
            block = allocator.apply(BLOCK_BYTES);
            blocks.add(block);
            offset = 0;
        }

        /**
         * Refuses a write that would carry the body past its limit, which is
         * where the growing buffer refused it too.
         */
        private void reserve(int extra) {
            if (length() + extra > MAX_BODY_BYTES) {
                throw new IllegalStateException("Analysis cache body exceeds its limit");
            }
        }

        /** Bytes written so far, which is the body length once encoded. */
        private long length() {
            return (long) (blocks.size() - 1) * BLOCK_BYTES + offset;
        }

        /** Hands the blocks written so far over as one body. */
        private BlockBody seal() {
            return new BlockBody(blocks, length());
        }
    }

    /**
     * A body held as fixed {@link #BLOCK_BYTES} blocks instead of as one array.
     * <p>
     * One array the size of the body is what this codec must never allocate.
     * Seventy megabytes is humongous at any heap size, so it is placed outside
     * the young generation and carried through every collection until the
     * encoding or the decoding ends; and it can only be produced by first
     * holding something else just as large. Blocks make the largest object a
     * constant, whatever the project.
     * <p>
     * The layout is known here and nowhere else: both directions take their
     * length, their checksum and their traversal from this class, so the two
     * cannot drift apart into writing one shape and reading another.
     * <p>
     * A decoded body is walked once and forwards. {@link #take} hands each
     * block over and forgets it, which is only sound once the body has been
     * proven whole, so {@link #checksum} and {@link #writeTo} are valid before
     * the first {@code take} and not after it.
     */
    private static final class BlockBody {

        private final List<byte[]> blocks;
        private final long length;

        private BlockBody(List<byte[]> blocks, long length) {
            this.blocks = blocks;
            this.length = length;
        }

        /**
         * Fills blocks straight from the stream.
         * <p>
         * {@link InputStream#readNBytes(int)} cannot be used for this: it
         * gathers the stream into chunks and then joins them into one array of
         * the full length, so it holds close to two bodies at its peak and
         * hands back an array of one. Reading block by block holds one body and
         * nothing over it.
         *
         * @throws AnalysisReplayFormatException if the stream ends early
         */
        static BlockBody readFrom(InputStream input, long length,
                IntFunction<byte[]> allocator) throws IOException {
            var blocks = new ArrayList<byte[]>(blockCount(length));
            long remaining = length;
            while (remaining > 0) {
                int wanted = (int) Math.min(remaining, BLOCK_BYTES);
                byte[] block = allocator.apply(BLOCK_BYTES);
                blocks.add(block);
                if (input.readNBytes(block, 0, wanted) != wanted) {
                    throw new AnalysisReplayFormatException(
                            "Analysis cache body is truncated");
                }
                remaining -= wanted;
            }
            return new BlockBody(blocks, length);
        }

        private static int blockCount(long length) {
            return (int) ((length + BLOCK_BYTES - 1) / BLOCK_BYTES);
        }

        private long length() {
            return length;
        }

        /**
         * Hands over one block and drops the body's own reference to it.
         * <p>
         * The payload a body decodes into outweighs the body several times
         * over - a seventy-megabyte cache builds a quarter of a gigabyte of
         * model - so holding the two together is what sets the heap a decoding
         * needs. Released as it is read, the body is gone by the time the
         * payload is at its largest.
         */
        private byte[] take(int index) {
            return blocks.set(index, null);
        }

        /** Bytes used in one block: every block but the last one is full. */
        private int filled(int index) {
            return (int) Math.min(BLOCK_BYTES, length - (long) index * BLOCK_BYTES);
        }

        private int checksum() {
            var crc = new CRC32C();
            for (int i = 0; i < blocks.size(); i++) {
                crc.update(blocks.get(i), 0, filled(i));
            }
            return (int) crc.getValue();
        }

        private void writeTo(OutputStream output) throws IOException {
            for (int i = 0; i < blocks.size(); i++) {
                output.write(blocks.get(i), 0, filled(i));
            }
        }
    }

    /**
     * Bounded body decoder, reading the blocks where they lie.
     * <p>
     * The cursor is the block holding the read point, the offset within it and
     * the offset one past its last valid byte. Those two being equal means
     * both "this block is spent" and, in the last block, "the body is spent",
     * so reading a byte costs the one compare it cost out of a single array.
     * The absolute position is derived rather than kept, because every limit of
     * this format is stated against the whole body and none of them is
     * consulted per byte.
     */
    private static final class BodyReader {

        private final BlockBody body;
        private final IntFunction<byte[]> allocator;
        private byte[] block;
        /** Body offset of {@link #block}; no block is loaded until a read. */
        private long start;
        private int offset;
        private int limit;
        private String[] strings;

        private BodyReader(BlockBody body, IntFunction<byte[]> allocator) {
            this.body = body;
            this.allocator = allocator;
        }

        private AnalysisReplayPayload decode() throws AnalysisReplayFormatException {
            int stringCount = readCount(MAX_STRINGS, "strings");
            strings = new String[stringCount];
            for (int i = 0; i < stringCount; i++) {
                strings[i] = readText(readCount(MAX_STRING_BYTES, "string length"));
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
            if (position() != body.length()) {
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
            if (count > body.length() - position()) {
                throw new AnalysisReplayFormatException(
                        "Analysis cache " + label + " count exceeds the remaining body");
            }
            return count;
        }

        /**
         * Reads a run of bytes as text, copying only when it straddles a block.
         *
         * @param length length of the run in bytes
         */
        private String readText(int length) throws AnalysisReplayFormatException {
            require(length);
            if (length == 0) {
                return "";
            }
            if (offset == limit) {
                advance();
            }
            if (length <= limit - offset) {
                var text = new String(block, offset, length, StandardCharsets.UTF_8);
                offset += length;
                return text;
            }
            // The only copy a decoding makes. It is one string long and never a
            // share of the body, and the String it becomes costs as much again
            // however the bytes are gathered, so nothing is saved by refusing
            // to gather them.
            byte[] straddling = allocator.apply(length);
            for (int copied = 0; copied < length;) {
                if (offset == limit) {
                    advance();
                }
                int chunk = Math.min(length - copied, limit - offset);
                System.arraycopy(block, offset, straddling, copied, chunk);
                offset += chunk;
                copied += chunk;
            }
            return new String(straddling, 0, length, StandardCharsets.UTF_8);
        }

        private int readByte() throws AnalysisReplayFormatException {
            if (offset == limit) {
                advance();
            }
            return block[offset++] & 0xFF;
        }

        /** Where the cursor stands in the body as a whole. */
        private long position() {
            return start + offset;
        }

        /**
         * Loads the block after the one just spent, which is also where that
         * one stops being referenced by anything.
         *
         * @throws AnalysisReplayFormatException if there is no next block,
         *                                       which is the body running out
         */
        private void advance() throws AnalysisReplayFormatException {
            long next = start + limit;
            if (next >= body.length()) {
                throw new AnalysisReplayFormatException("Analysis cache body is truncated");
            }
            int index = (int) (next / BLOCK_BYTES);
            block = body.take(index);
            start = next;
            limit = body.filled(index);
            offset = 0;
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
            if (bytes < 0 || bytes > body.length() - position()) {
                throw new AnalysisReplayFormatException("Analysis cache body is truncated");
            }
        }
    }
}
