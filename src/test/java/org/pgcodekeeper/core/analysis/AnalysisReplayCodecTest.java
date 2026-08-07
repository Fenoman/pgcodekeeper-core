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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.zip.CRC32C;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.DangerStatement;
import org.pgcodekeeper.core.analysis.AnalysisReplayPayload.FileReferences;
import org.pgcodekeeper.core.analysis.AnalysisReplayPayload.StatementDependencies;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation.LocationType;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;

/**
 * Guards the two things an analysis cache codec owes its callers: the bytes,
 * which the previous release has to keep reading and writing, and the memory it
 * takes to move them either way.
 * <p>
 * The memory is not a matter of taste here. Both directions run in the IDE, on a
 * heap that is already holding the model the payload was captured from or is
 * about to hold the model it describes, and a body of seventy megabytes used to
 * cost three times that in each direction - the array being outgrown, its
 * replacement and the exact-sized copy on the way out; the chunks, their joined
 * array and the payload on the way in - which is what an
 * {@code OutOfMemoryError} in the workspace log was made of.
 *
 * @see #everyBodyAllocationIsExactlyOneBlock
 * @see #noArrayOfADecodingIsLargerThanABlock
 * @see FrozenAnalysisReplayCodec
 */
class AnalysisReplayCodecTest {

    private static final int FILES = 6;
    private static final int LOCATIONS_PER_FILE = 1000;
    private static final int SQL_BYTES = 1000;
    private static final int STATEMENTS = 400;
    private static final int SUPPRESSED = 50;
    private static final int STATEMENT_COUNT = 12_345;

    /**
     * G1 calls an object humongous once it reaches half a region, and the
     * smallest region it ever uses is one megabyte, whatever {@code -Xmx} says.
     */
    private static final int HUMONGOUS_BYTES = 1 << 19;

    /** Below this a seventy-megabyte body would need thousands of blocks. */
    private static final int USEFUL_BLOCK_BYTES = 1 << 16;

    /** What the codec allows one string of the dictionary to weigh. */
    private static final int MAX_STRING_BYTES = 1 << 20;

    private static final String SPANNING_FILE = "SCHEMA/app/VIEW/spanning.sql";
    private static final String PADDING = "0123456789abcdef".repeat(4);

    private static final LocationType[] TYPES = LocationType.values();
    private static final DangerStatement[] DANGERS = DangerStatement.values();

    /** Carries nothing, so that its container is a bare header to borrow. */
    private static final AnalysisReplayPayload EMPTY =
            new AnalysisReplayPayload(List.of(), List.of(), List.of(), 0);

    /** Several megabytes, one string of which no single block can hold. */
    private static AnalysisReplayPayload fixture;

    /** The same payload without that string, so a block can bound every array. */
    private static AnalysisReplayPayload ordinary;

    @BeforeAll
    static void buildTheFixtures() {
        List<FileReferences> files = severalMegabytes();
        var withSpanning = new ArrayList<>(files);
        withSpanning.add(new FileReferences(SPANNING_FILE, List.of(
                location(SPANNING_FILE, 0, filler("spanning", spanningLength(1))))));
        fixture = payload(withSpanning);
        ordinary = payload(files);
    }

    /**
     * The test the fix exists for: the encoder must never hold an array larger
     * than one block, however large the payload is.
     * <p>
     * The block allocator is the only place the encoder takes memory for the
     * body, so counting what goes through it is the whole memory shape of an
     * encoding, measured rather than guessed at - free-heap readings prove
     * nothing about which array was live when.
     */
    @Test
    void everyBodyAllocationIsExactlyOneBlock() throws Exception {
        var sizes = new ArrayList<Integer>();
        long written = AnalysisReplayCodec.write(fixture,
                OutputStream.nullOutputStream(), counting(sizes));
        long body = written - AnalysisReplayCodec.HEADER_BYTES;

        assertTrue(body > 8L * AnalysisReplayCodec.BLOCK_BYTES,
                "the fixture must be large enough to need many blocks: " + body);
        for (int size : sizes) {
            assertEquals(AnalysisReplayCodec.BLOCK_BYTES, size,
                    "the encoder must allocate nothing but whole blocks");
        }
        assertEquals(blocksNeeded(body), sizes.size(),
                "the encoder must take exactly the blocks the body needs");
        long held = (long) sizes.size() * AnalysisReplayCodec.BLOCK_BYTES;
        assertTrue(held < body + AnalysisReplayCodec.BLOCK_BYTES,
                "an encoding must hold the body and at most one block over it: "
                        + held + " held for a body of " + body);
    }

    /**
     * The same test for the other direction: a decoding must never hold an
     * array larger than one block either.
     * <p>
     * It used to hold two arrays the size of the whole body -
     * {@link java.io.InputStream#readNBytes(int)} gathers a stream into chunks
     * and then joins them - and the join is the humongous object that survived
     * into the decoding and was carried through every collection the payload
     * caused. Counting what the allocator handed out is the whole memory shape
     * of a decoding, measured rather than guessed at.
     */
    @Test
    void noArrayOfADecodingIsLargerThanABlock() throws Exception {
        byte[] container = encode(ordinary);
        var sizes = new ArrayList<Integer>();
        AnalysisReplayPayload decoded = decode(container, counting(sizes));
        long body = container.length - AnalysisReplayCodec.HEADER_BYTES;

        assertPayloadsEqual(ordinary, decoded);
        assertTrue(body > 8L * AnalysisReplayCodec.BLOCK_BYTES,
                "the fixture must be large enough to need many blocks: " + body);
        for (int size : sizes) {
            assertTrue(size <= AnalysisReplayCodec.BLOCK_BYTES,
                    "a decoding must allocate nothing larger than a block: " + size);
        }
        assertEquals(blocksNeeded(body), sizes.stream()
                .filter(size -> size == AnalysisReplayCodec.BLOCK_BYTES).count(),
                "a decoding must take exactly the blocks the body needs");
        long held = sizes.stream().mapToLong(Integer::longValue).sum();
        assertTrue(held < body + 2L * AnalysisReplayCodec.BLOCK_BYTES,
                "a decoding must hold one body and no second copy of it: "
                        + held + " taken for a body of " + body);
    }

    /**
     * A string can be longer than a block, and then it cannot be read where it
     * lies. That is the one copy a decoding is allowed to make, so it has to be
     * pinned to exactly what it is: the length of that string and of nothing
     * else, which the {@code String} it becomes costs again whichever way the
     * bytes are gathered.
     */
    @Test
    void theOnlyArrayOverABlockIsAStringNoBlockCanHold() throws Exception {
        String sql = filler("spanning", spanningLength(2));
        var payload = new AnalysisReplayPayload(List.of(),
                List.of(new FileReferences(SPANNING_FILE,
                        List.of(location(SPANNING_FILE, 0, sql)))),
                List.of(), 1);
        byte[] container = encode(payload);

        var sizes = new ArrayList<Integer>();
        AnalysisReplayPayload decoded = decode(container, counting(sizes));

        assertEquals(sql, decoded.references().get(0).locations().get(0).getSql(),
                "a string that spans blocks must survive the decoding");
        assertEquals(List.of(sql.getBytes(StandardCharsets.UTF_8).length),
                sizes.stream()
                        .filter(size -> size > AnalysisReplayCodec.BLOCK_BYTES).toList(),
                "the only array over a block must be that string itself");
    }

    /**
     * The order the whole fail-closed design rests on: a body is proven whole
     * before a byte of it is given a meaning.
     * <p>
     * Reading the body in blocks makes it tempting to decode as the blocks
     * arrive and check the checksum at the end. This container would then be
     * reported by the limit its damage happens to trip rather than as damaged,
     * and everything the decoder does before that point would have been done on
     * bytes nothing vouched for.
     */
    @Test
    void aDamagedBodyFailsItsChecksumBeforeItIsReadAtAll() throws Exception {
        byte[] container = encode(ordinary);
        // A string count of 2^28-1, which is both past the limit on string
        // counts and a well-formed varint, so a decoder that ran first would
        // have that limit to report and would report it.
        int body = AnalysisReplayCodec.HEADER_BYTES;
        Arrays.fill(container, body, body + 3, (byte) 0xFF);
        container[body + 3] = 0x7F;

        AnalysisReplayFormatException failure = assertThrows(
                AnalysisReplayFormatException.class, () -> decode(container));
        assertEquals("Analysis cache body fails its checksum", failure.getMessage(),
                "integrity has to be settled before a byte of meaning is taken");
    }

    /**
     * A body that stops exactly where a block does is the one truncation a
     * block reader can mistake for a whole body.
     */
    @Test
    void aBodyCutWhereABlockEndsIsReportedAsTruncated() throws Exception {
        byte[] container = encode(ordinary);
        int kept = AnalysisReplayCodec.HEADER_BYTES + 4 * AnalysisReplayCodec.BLOCK_BYTES;
        assertTrue(kept < container.length, "the fixture must outlast four blocks");

        AnalysisReplayFormatException failure = assertThrows(
                AnalysisReplayFormatException.class,
                () -> decode(Arrays.copyOf(container, kept)));
        assertEquals("Analysis cache body is truncated", failure.getMessage(),
                "a body that ends early is truncated, whatever its checksum says");
    }

    /**
     * The body is let go of as it is read, rather than held whole until the
     * payload is finished.
     * <p>
     * This is where the heap a decoding needs is settled. A payload outweighs
     * the body it was decoded from several times over - seventy megabytes of
     * container become a quarter of a gigabyte of model - so holding the two
     * together costs a whole body of heap at the moment the payload is at its
     * largest, and it is the moment the heap was found to run out at. No count
     * of allocations can show this, because the question is not what was taken
     * but what is still reachable, so this is the one test here that has to ask
     * the collector.
     * <p>
     * A string that straddles a block asks the allocator for the run to gather
     * it into, and it does so while the read point stands in the first of the
     * two blocks. Every block before that one has therefore been read to its
     * end, which is the observation the bound below is made of.
     * <p>
     * The bound is met exactly, with no block to spare, under G1, Serial,
     * Parallel, ZGC and the interpreter alike. What it does rest on is
     * {@link System#gc()} collecting: under {@code -XX:+DisableExplicitGC}
     * nothing is ever cleared and this test cannot be answered at all.
     */
    @Test
    void aDecodingLetsGoOfTheBodyBehindItsReadPoint() throws Exception {
        byte[] container = encode(ordinary);
        var blocks = new ArrayList<Reference<byte[]>>();
        var live = new ArrayList<Integer>();
        decode(container, size -> {
            byte[] array = new byte[size];
            if (size == AnalysisReplayCodec.BLOCK_BYTES) {
                blocks.add(new WeakReference<>(array));
            } else {
                live.add(liveBlocks(blocks));
            }
            return array;
        });

        assertTrue(live.size() > 4,
                "the fixture must straddle several blocks to be worth asking: "
                        + live.size());
        // Every block is taken before the first straddle, so their number is
        // settled by then; the n-th straddle stands at least n-1 boundaries in,
        // and everything behind the read point has to be gone.
        int held = live.get(live.size() - 1);
        int allowed = blocks.size() - live.size() + 1;
        assertTrue(held <= allowed,
                "a decoding must hold the block it reads and nothing behind it,"
                        + " and held " + held + " of " + blocks.size()
                        + " at straddle " + live.size() + ", where " + allowed
                        + " is what is still ahead of the read point");
    }

    /** How many of the blocks handed out are still reachable. */
    private static int liveBlocks(List<Reference<byte[]>> blocks) {
        System.gc();
        int live = 0;
        for (Reference<byte[]> block : blocks) {
            if (block.get() != null) {
                live++;
            }
        }
        return live;
    }

    /**
     * A body can end where the format expects another byte and still agree with
     * its own header, and then the block reader's own bound is all there is.
     * <p>
     * Every other damage is settled before a byte is interpreted: a body that
     * does not arrive whole is truncated, one that arrives changed fails its
     * checksum. A body that is short, says so, and carries a checksum over what
     * it does have passes both and runs out mid-structure anyway. Running out
     * of blocks has to be reported as the body ending, not raised as whatever
     * happens to lie where the next block would have been - which is a
     * different thing on each side of a block boundary, so both are asked.
     */
    @Test
    void aBodyThatRunsOutOfBlocksIsReportedAsTruncated() throws Exception {
        // One byte that promises another, and then nothing: the reader is left
        // inside the last block it holds.
        byte[] promise = {(byte) 0x80};
        for (byte[] body : List.of(promise, oneWholeBlock())) {
            AnalysisReplayFormatException failure = assertThrows(
                    AnalysisReplayFormatException.class,
                    () -> decode(containerOf(body)),
                    "a body of " + body.length + " bytes");
            assertEquals("Analysis cache body is truncated", failure.getMessage(),
                    "a body of " + body.length + " bytes");
        }
    }

    /**
     * A body of exactly one whole block: a dictionary of one string filling it
     * to the last byte, so that the count which has to follow the dictionary is
     * the byte the body does not have and the reader is left looking for a
     * block past the last one.
     */
    private static byte[] oneWholeBlock() {
        int size = AnalysisReplayCodec.BLOCK_BYTES;
        var body = new ByteArrayOutputStream(size);
        writeVarInt(body, 1);
        writeVarInt(body, size - 4);
        body.writeBytes(filler("block", size - 4).getBytes(StandardCharsets.UTF_8));
        assertEquals(size, body.size(), "the body must come to exactly one block");
        return body.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream body, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            body.write(remaining & 0x7F | 0x80);
            remaining >>>= 7;
        }
        body.write(remaining);
    }

    /**
     * Wraps a body of the test's own making in the header that describes it,
     * which is the only way to reach the decoder's own bounds at all.
     */
    private static byte[] containerOf(byte[] body) throws Exception {
        byte[] container = Arrays.copyOf(encode(EMPTY),
                AnalysisReplayCodec.HEADER_BYTES + body.length);
        System.arraycopy(body, 0, container, AnalysisReplayCodec.HEADER_BYTES,
                body.length);
        var crc = new CRC32C();
        crc.update(body);
        putInt(container, 12, 0);
        putInt(container, 16, body.length);
        putInt(container, 20, (int) crc.getValue());
        return container;
    }

    private static void putInt(byte[] target, int offset, int value) {
        for (int i = 0; i < 4; i++) {
            target[offset + i] = (byte) (value >>> 8 * (3 - i));
        }
    }

    /**
     * The store puts its own header before the container and checks for
     * trailing bytes after it, so a reader that took its body a block at a time
     * must still stop on the byte the body ends at.
     */
    @Test
    void aDecodingStopsOnTheByteItsContainerEndsOn() throws Exception {
        byte[] container = encode(ordinary);
        byte[] trailing = {7, 11, 13};
        byte[] both = Arrays.copyOf(container, container.length + trailing.length);
        System.arraycopy(trailing, 0, both, container.length, trailing.length);

        var stream = new ByteArrayInputStream(both);
        assertPayloadsEqual(ordinary, AnalysisReplayCodec.read(stream));
        assertArrayEquals(trailing, stream.readAllBytes(),
                "the reader must leave the stream on the byte after the container");
    }

    /**
     * Pins the block size against the two mistakes it can make: a block that
     * lands outside the young generation, and one so small the list of them
     * becomes the problem instead.
     */
    @Test
    void aBlockIsTooSmallToBeHumongousAndLargeEnoughToBeWorthIt() {
        assertTrue(AnalysisReplayCodec.BLOCK_BYTES < HUMONGOUS_BYTES,
                "a block must stay below the smallest humongous threshold");
        assertTrue(AnalysisReplayCodec.BLOCK_BYTES >= USEFUL_BLOCK_BYTES,
                "a block must be large enough to keep the block list short");
    }

    /**
     * The container is a file the user already has on disk, so the encoder may
     * only be rewritten while it writes the very same bytes.
     */
    @Test
    void theEncoderWritesWhatTheShippedOneWrote() throws Exception {
        assertContainersEqual(shippedEncode(fixture), encode(fixture));
    }

    /** A cache written by the shipped release must still be readable. */
    @Test
    void aContainerOfTheShippedEncoderReadsBackUnchanged() throws Exception {
        byte[] shipped = shippedEncode(fixture);
        assertPayloadsEqual(fixture, decode(shipped));
    }

    /** A cache written now must still be readable by the shipped release. */
    @Test
    void theShippedDecoderReadsWhatTheEncoderWrites() throws Exception {
        byte[] current = encode(fixture);
        assertPayloadsEqual(fixture, FrozenAnalysisReplayCodec.read(
                new ByteArrayInputStream(current)));
    }

    /**
     * A string is written as one run of bytes and can be four times a block, so
     * the only write that spans blocks has to be proven, not assumed.
     */
    @Test
    void aStringLongerThanABlockSurvivesTheEncoding() throws Exception {
        String sql = filler("spanning", spanningLength(2));
        var payload = new AnalysisReplayPayload(List.of(),
                List.of(new FileReferences(SPANNING_FILE,
                        List.of(location(SPANNING_FILE, 0, sql)))),
                List.of(), 1);

        byte[] container = encode(payload);
        assertEquals(sql, decode(container).references().get(0).locations().get(0)
                .getSql(), "a string that spans blocks must survive the encoding");
        assertContainersEqual(shippedEncode(payload), container);
    }

    /**
     * Length of a string that the given number of blocks cannot hold, kept
     * within what the codec allows one string to weigh: the block size is a
     * memory decision and must not be able to break the fixture.
     */
    private static int spanningLength(int blocks) {
        return Math.min(AnalysisReplayCodec.BLOCK_BYTES * blocks + 7, MAX_STRING_BYTES);
    }

    private static long blocksNeeded(long body) {
        int block = AnalysisReplayCodec.BLOCK_BYTES;
        return Math.max(1, (body + block - 1) / block);
    }

    private static byte[] encode(AnalysisReplayPayload payload) throws Exception {
        var bytes = new ByteArrayOutputStream();
        AnalysisReplayCodec.write(payload, bytes);
        return bytes.toByteArray();
    }

    private static byte[] shippedEncode(AnalysisReplayPayload payload) throws Exception {
        var bytes = new ByteArrayOutputStream();
        FrozenAnalysisReplayCodec.write(payload, bytes);
        return bytes.toByteArray();
    }

    private static AnalysisReplayPayload decode(byte[] container) throws Exception {
        return AnalysisReplayCodec.read(new ByteArrayInputStream(container));
    }

    private static AnalysisReplayPayload decode(byte[] container,
            IntFunction<byte[]> allocator) throws Exception {
        return AnalysisReplayCodec.read(new ByteArrayInputStream(container), allocator);
    }

    /** An allocator that records the size of every array it hands out. */
    private static IntFunction<byte[]> counting(List<Integer> sizes) {
        return size -> {
            sizes.add(size);
            return new byte[size];
        };
    }

    private static void assertContainersEqual(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length, "container length");
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                assertEquals(expected[i], actual[i], "container byte " + i);
            }
        }
    }

    private static void assertPayloadsEqual(AnalysisReplayPayload expected,
            AnalysisReplayPayload actual) {
        assertEquals(expected.statementCount(), actual.statementCount(),
                "statement count");
        assertEquals(expected.dependencies(), actual.dependencies(), "dependencies");
        assertEquals(expected.suppressedRoutines(), actual.suppressedRoutines(),
                "suppressed routines");
        assertEquals(expected.references().stream().map(FileReferences::filePath).toList(),
                actual.references().stream().map(FileReferences::filePath).toList(),
                "reference files");
        for (int i = 0; i < expected.references().size(); i++) {
            List<ObjectLocation> want = expected.references().get(i).locations();
            List<ObjectLocation> have = actual.references().get(i).locations();
            assertEquals(want.size(), have.size(), "location count of file " + i);
            for (int j = 0; j < want.size(); j++) {
                assertLocationsEqual(want.get(j), have.get(j),
                        "location " + j + " of file " + i);
            }
        }
    }

    /**
     * Compares a location field by field: its own equality looks at three
     * fields only, so an encoding that loses an offset or an alias would pass
     * a plain comparison.
     */
    private static void assertLocationsEqual(ObjectLocation expected,
            ObjectLocation actual, String where) {
        Map<String, Object> want = fields(expected);
        Map<String, Object> have = fields(actual);
        for (Map.Entry<String, Object> field : want.entrySet()) {
            assertEquals(field.getValue(), have.get(field.getKey()),
                    where + ", field " + field.getKey());
        }
    }

    private static Map<String, Object> fields(ObjectLocation location) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("filePath", location.getFilePath());
        fields.put("offset", location.getOffset());
        fields.put("lineNumber", location.getLineNumber());
        fields.put("charPositionInLine", location.getCharPositionInLine());
        fields.put("objLength", location.getObjLength());
        fields.put("action", location.getAction());
        fields.put("sql", location.getSql());
        fields.put("alias", location.getAlias());
        fields.put("locationType", location.getLocationType());
        fields.put("danger", location.getDanger());
        fields.put("objectReference", location.getObjectReference());
        return fields;
    }

    /**
     * Builds files whose encoding is several megabytes, which is what makes the
     * block count large enough for the allocation assertions to mean anything.
     */
    private static List<FileReferences> severalMegabytes() {
        var files = new ArrayList<FileReferences>(FILES);
        for (int file = 0; file < FILES; file++) {
            String path = "SCHEMA/app/TABLE/t" + file + ".sql";
            var locations = new ArrayList<ObjectLocation>(LOCATIONS_PER_FILE);
            for (int i = 0; i < LOCATIONS_PER_FILE; i++) {
                locations.add(location(path, i, filler("sql-" + file + '-' + i, SQL_BYTES)));
            }
            files.add(new FileReferences(path, locations));
        }
        return files;
    }

    private static AnalysisReplayPayload payload(List<FileReferences> files) {
        var dependencies = new ArrayList<StatementDependencies>(STATEMENTS);
        var suppressed = new ArrayList<StatementAddress>(SUPPRESSED);
        for (int i = 0; i < STATEMENTS; i++) {
            dependencies.add(new StatementDependencies(address(i), references(i)));
            if (i < SUPPRESSED) {
                suppressed.add(address(STATEMENTS + i));
            }
        }
        return new AnalysisReplayPayload(
                dependencies, files, suppressed, STATEMENT_COUNT);
    }

    private static ObjectLocation location(String path, int index, String sql) {
        var location = new ObjectLocation.Builder()
                .setFilePath(path)
                .setOffset(index * 17)
                .setLineNumber(index / 4 + 1)
                .setCharPositionInLine(index % 40)
                .setLength(index % 23 + 1)
                .setAction("SELECT " + index % 5)
                .setSql(sql)
                // Null fields are the cheapest thing for an encoding to lose,
                // so every payload has to carry some.
                .setAlias(index % 3 == 0 ? null : "a" + index % 7)
                .setLocationType(TYPES[index % TYPES.length])
                .setReference(new ObjectReference("app", "t" + index % 11,
                        index % 2 == 0 ? null : "c" + index % 13, DbObjType.COLUMN))
                .build();
        if (index % 5 == 0) {
            location.setWarning(DANGERS[index % DANGERS.length]);
        }
        return location;
    }

    private static StatementAddress address(int index) {
        return new StatementAddress(List.of(
                new StatementAddress.Segment(DbObjType.SCHEMA, "app"),
                new StatementAddress.Segment(DbObjType.TABLE, "t" + index),
                new StatementAddress.Segment(DbObjType.COLUMN, "c" + index)));
    }

    private static List<ObjectReference> references(int index) {
        return List.of(
                new ObjectReference("app", DbObjType.SCHEMA),
                new ObjectReference("app", "t" + index, DbObjType.TABLE),
                new ObjectReference("app", "t" + index, "c" + index, DbObjType.COLUMN));
    }

    /**
     * Builds a distinct string of the requested length: the dictionary stores
     * every string once, so a payload of repeated text would encode to almost
     * nothing.
     */
    private static String filler(String seed, int length) {
        var text = new StringBuilder(length);
        text.append(seed).append(':');
        while (text.length() < length) {
            text.append(PADDING, 0, Math.min(PADDING.length(), length - text.length()));
        }
        return text.toString();
    }
}
