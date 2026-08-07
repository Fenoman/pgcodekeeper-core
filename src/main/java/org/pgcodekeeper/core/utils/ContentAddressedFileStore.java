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
package org.pgcodekeeper.core.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Content-addressed local file store keyed by SHA-256. Entries live under
 * {@code <root>/<category>/<first-2-hex>/<sha256>-<qualifier>.bin} and hold
 * raw payload bytes whose SHA-256 digest equals the addressed hash.
 * <p>
 * Cache contract: every operation is best-effort and never throws on I/O
 * problems. Reads re-verify the content hash before returning; a corrupt
 * entry is deleted and reported as a miss. Writes verify the payload against
 * its address, stage into a temp file in the target directory and publish
 * with an atomic rename ({@code ATOMIC_MOVE}, falling back to a plain move on
 * filesystems without atomic rename support).
 * <p>
 * Concurrency: safe for multiple threads and multiple processes sharing one
 * directory. Concurrent writers of the same entry converge on identical
 * bytes, so whichever rename lands last (or first) is equally valid; readers
 * only ever observe fully published, hash-verified entries. Pruning uses a
 * non-blocking file lock and skips the attempt when another owner is pruning.
 * <p>
 * Externally keyed entries
 * ({@link #readKeyed(String, String, String, int)},
 * {@link #writeKeyed(String, String, String, byte[], int)}) relax the
 * content-addressing contract: their key is a hash computed by an external
 * authority (for example a server-side {@code md5(row::text)}) that cannot be
 * re-derived from the stored payload, so the store cannot verify them on read
 * or write. Integrity of keyed payloads is therefore the caller's
 * responsibility, typically through an internal payload checksum that
 * degrades a mismatch to a cache miss. Callers must also provide a positive
 * payload limit so externally supplied files are read with bounded memory.
 */
public final class ContentAddressedFileStore {

    private static final Logger LOG = LoggerFactory.getLogger(ContentAddressedFileStore.class);

    private static final String ENTRY_EXTENSION = ".bin";
    private static final String TEMP_EXTENSION = ".tmp";
    public static final String PRUNE_LOCK_FILE = ".pgck-prune.lock";
    private static final String READER_PACKS_DIRECTORY = "reader-packs";
    private static final int SHA_256_HEX_LENGTH = 64;
    private static final int MD5_HEX_LENGTH = 32;
    private static final int TEXT_CHUNK_CHARS = 8 * 1024;
    /**
     * Cached routine sources and catalog rows describe one exact database, so
     * the store keeps its directories and lock files private to their owner.
     * File systems without POSIX permissions (Windows) fall back to the
     * platform default, exactly as before; payload files are already created
     * private by {@link Files#createTempFile(Path, String, String,
     * FileAttribute...)}.
     */
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.fromString("rwx------");
    private static final FileAttribute<?>[] NO_ATTRIBUTES = {};

    private final Path root;

    public ContentAddressedFileStore(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /**
     * Reads a verified entry.
     *
     * @param category   entry namespace, one directory level
     * @param sha256Hex  64 lowercase hex characters of the content SHA-256
     * @param qualifier  extra identity appended to the file name
     * @return payload bytes whose SHA-256 equals {@code sha256Hex}, or
     *         {@code null} when the entry is absent or corrupt (corrupt
     *         entries are deleted)
     */
    public byte[] read(String category, String sha256Hex, String qualifier) {
        Path entry = entryPath(category, sha256Hex, qualifier);
        byte[] content;
        try {
            content = Files.readAllBytes(entry);
        } catch (IOException ex) {
            return null;
        }
        if (sha256Hex.equals(sha256Hex(content))) {
            return content;
        }
        LOG.debug("Deleting corrupt content-addressed store entry {}", entry);
        delete(category, sha256Hex, qualifier);
        return null;
    }

    /**
     * Publishes an entry atomically.
     *
     * @param category  entry namespace, one directory level
     * @param sha256Hex 64 lowercase hex characters of the content SHA-256
     * @param qualifier extra identity appended to the file name
     * @param content   payload bytes; must hash to {@code sha256Hex}
     * @return {@code true} when the entry was newly published, {@code false}
     *         when it already existed, the payload did not match its address
     *         or an I/O problem prevented publication
     */
    public boolean write(String category, String sha256Hex, String qualifier, byte[] content) {
        Objects.requireNonNull(content, "content");
        Path entry = entryPath(category, sha256Hex, qualifier);
        if (Files.exists(entry)) {
            return false;
        }
        if (!sha256Hex.equals(sha256Hex(content))) {
            LOG.debug("Refusing to store payload that does not match its address {}", entry);
            return false;
        }

        Path temp = null;
        try {
            createPrivateDirectories(entry.getParent());
            temp = Files.createTempFile(entry.getParent(), sha256Hex, TEMP_EXTENSION);
            Files.write(temp, content);
            publish(temp, entry);
            return true;
        } catch (IOException ex) {
            LOG.debug("Failed to store content-addressed entry {}", entry, ex);
            deleteQuietly(temp);
            return false;
        }
    }

    /**
     * Reads a verified UTF-8 text entry without materializing a payload-sized
     * byte array: bytes stream through the digest into the decoder and only
     * the resulting String is retained.
     *
     * @param category           entry namespace, one directory level
     * @param sha256Hex          64 lowercase hex characters of the UTF-8 SHA-256
     * @param qualifier          extra identity appended to the file name
     * @param expectedUtf8Length expected payload byte size, used as a sizing
     *                           hint for the decoded text
     * @return decoded text whose strict UTF-8 encoding hashes to
     *         {@code sha256Hex}, or {@code null} when the entry is absent,
     *         undecodable or corrupt (invalid entries are deleted)
     */
    public String readUtf8(String category, String sha256Hex, String qualifier,
                           long expectedUtf8Length) {
        Path entry = entryPath(category, sha256Hex, qualifier);
        MessageDigest digest = newSha256();
        var text = new StringBuilder(
                (int) Math.min(Math.max(expectedUtf8Length, 0L), Integer.MAX_VALUE - 8L));
        char[] chunk = new char[TEXT_CHUNK_CHARS];
        try (Reader reader = new InputStreamReader(
                new DigestInputStream(
                        new BufferedInputStream(Files.newInputStream(entry)), digest),
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT))) {
            int read;
            while ((read = reader.read(chunk)) >= 0) {
                text.append(chunk, 0, read);
            }
        } catch (NoSuchFileException ex) {
            return null;
        } catch (IOException ex) {
            LOG.debug("Deleting undecodable content-addressed store entry {}", entry, ex);
            deleteQuietly(entry);
            return null;
        }
        if (sha256Hex.equals(HexFormat.of().formatHex(digest.digest()))) {
            return text.toString();
        }
        LOG.debug("Deleting corrupt content-addressed store entry {}", entry);
        deleteQuietly(entry);
        return null;
    }

    /**
     * Publishes a UTF-8 text entry atomically without materializing a
     * payload-sized byte array: characters stream through the encoder and the
     * digest into a temp file that is published only after the observed hash
     * matches the address. Malformed UTF-16 content is never published.
     *
     * @param category  entry namespace, one directory level
     * @param sha256Hex 64 lowercase hex characters of the UTF-8 SHA-256
     * @param qualifier extra identity appended to the file name
     * @param content   text whose strict UTF-8 encoding must hash to
     *                  {@code sha256Hex}
     * @return {@code true} when the entry was newly published
     */
    public boolean writeUtf8(String category, String sha256Hex, String qualifier,
                             String content) {
        Objects.requireNonNull(content, "content");
        Path entry = entryPath(category, sha256Hex, qualifier);
        if (Files.exists(entry)) {
            return false;
        }

        MessageDigest digest = newSha256();
        Path temp = null;
        try {
            createPrivateDirectories(entry.getParent());
            temp = Files.createTempFile(entry.getParent(), sha256Hex, TEMP_EXTENSION);
            try (Writer writer = new OutputStreamWriter(
                    new DigestOutputStream(
                            new BufferedOutputStream(Files.newOutputStream(temp)), digest),
                    StandardCharsets.UTF_8.newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT))) {
                writer.write(content);
            }
            if (!sha256Hex.equals(HexFormat.of().formatHex(digest.digest()))) {
                LOG.debug("Refusing to store text that does not match its address {}", entry);
                deleteQuietly(temp);
                return false;
            }
            publish(temp, entry);
            return true;
        } catch (IOException ex) {
            LOG.debug("Failed to store content-addressed text entry {}", entry, ex);
            deleteQuietly(temp);
            return false;
        }
    }

    /**
     * Quietly removes an entry if it exists.
     */
    public void delete(String category, String sha256Hex, String qualifier) {
        deleteQuietly(entryPath(category, sha256Hex, qualifier));
    }

    /**
     * Reads an externally keyed entry without content verification; the
     * caller must validate the payload itself (see the class contract).
     * Entries larger than the supplied cap are deleted and reported as
     * misses. The size is checked before opening the stream and the stream is
     * still read with one sentinel byte so concurrent growth cannot cause an
     * unbounded allocation.
     *
     * @param category entry namespace, one directory level
     * @param keyHex   32 or 64 lowercase hex characters of the external key
     * @param qualifier extra identity appended to the file name
     * @param maxPayloadBytes positive payload cap smaller than
     *                        {@link Integer#MAX_VALUE}
     * @return payload bytes, or {@code null} when the entry is absent or
     *         unreadable, or exceeds the payload cap
     * @throws IllegalArgumentException when {@code maxPayloadBytes} is not
     *                                  positive or leaves no room for the
     *                                  sentinel byte
     */
    public byte[] readKeyed(String category, String keyHex, String qualifier,
            int maxPayloadBytes) {
        Path entry = keyedEntryPath(category, keyHex, qualifier);
        requireKeyedPayloadLimit(maxPayloadBytes);
        try {
            if (Files.size(entry) > maxPayloadBytes) {
                LOG.debug("Deleting oversized keyed store entry {}", entry);
                deleteQuietly(entry);
                return null;
            }
            byte[] content;
            try (var input = Files.newInputStream(entry)) {
                content = input.readNBytes(maxPayloadBytes + 1);
            }
            if (content.length > maxPayloadBytes) {
                LOG.debug("Deleting oversized keyed store entry {}", entry);
                deleteQuietly(entry);
                return null;
            }
            return content;
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * Publishes an externally keyed entry atomically. The payload is stored
     * as given: the store cannot check it against the external key.
     *
     * @param category entry namespace, one directory level
     * @param keyHex   32 or 64 lowercase hex characters of the external key
     * @param qualifier extra identity appended to the file name
     * @param content  payload bytes
     * @param maxPayloadBytes positive payload cap smaller than
     *                        {@link Integer#MAX_VALUE}
     * @return {@code true} when the entry was newly published, {@code false}
     *         when it already existed, the payload exceeds the cap or an I/O
     *         problem prevented publication
     * @throws IllegalArgumentException when {@code maxPayloadBytes} is not
     *                                  positive or leaves no room for the
     *                                  sentinel byte
     */
    public boolean writeKeyed(String category, String keyHex, String qualifier,
            byte[] content, int maxPayloadBytes) {
        Objects.requireNonNull(content, "content");
        Path entry = keyedEntryPath(category, keyHex, qualifier);
        requireKeyedPayloadLimit(maxPayloadBytes);
        if (content.length > maxPayloadBytes) {
            LOG.debug("Refusing to store oversized keyed payload at {}", entry);
            return false;
        }
        if (Files.exists(entry)) {
            return false;
        }
        Path temp = null;
        try {
            createPrivateDirectories(entry.getParent());
            temp = Files.createTempFile(entry.getParent(), keyHex, TEMP_EXTENSION);
            Files.write(temp, content);
            publish(temp, entry);
            return true;
        } catch (IOException ex) {
            LOG.debug("Failed to store keyed entry {}", entry, ex);
            deleteQuietly(temp);
            return false;
        }
    }

    /**
     * Reports whether an externally keyed entry currently exists. A positive
     * probe is not a read guarantee: concurrent pruning may remove the entry
     * before a later {@link #readKeyed(String, String, String, int)} call.
     */
    public boolean containsKeyed(String category, String keyHex, String qualifier) {
        return Files.exists(keyedEntryPath(category, keyHex, qualifier));
    }

    /**
     * Quietly removes an externally keyed entry if it exists.
     */
    public void deleteKeyed(String category, String keyHex, String qualifier) {
        deleteQuietly(keyedEntryPath(category, keyHex, qualifier));
    }

    /**
     * Prunes oldest-modified published entries first until the store fits the
     * byte cap. Temporary files and the prune lock are never counted or
     * removed. PostgreSQL reader packs are counted, but their immutable pack
     * and manifest files are left to their pack-aware pruner so a generic
     * per-file pass cannot split a published generation. When another process
     * or thread owns the prune lock, this call skips immediately.
     * <p>
     * Reader packs share the cap with the deletable entries but can never be
     * removed here, so the budget for deletable entries is the cap minus the
     * bytes held by packs. When packs alone already fill the cap this call
     * removes nothing: deleting every deletable entry would not bring the
     * store under the cap and would only wipe a cache that the pack-aware
     * pruner is about to make room for.
     *
     * @param maxBytes positive total size cap in bytes
     * @return bytes removed by this call
     */
    public long pruneToLimit(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("Store size cap must be positive");
        }
        Path pruneRoot;
        try {
            pruneRoot = root.toRealPath();
        } catch (IOException ex) {
            LOG.debug("Skipping content-addressed store pruning; root unavailable {}",
                    root, ex);
            return 0L;
        }
        if (!Files.isDirectory(pruneRoot)) {
            return 0L;
        }

        Path lockPath = pruneRoot.resolve(PRUNE_LOCK_FILE);
        try (FileChannel channel = FileChannel.open(lockPath,
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                privateAttributes(lockPath, OWNER_ONLY_FILE))) {
            FileLock acquired;
            try {
                acquired = channel.tryLock();
            } catch (OverlappingFileLockException | IOException ex) {
                LOG.debug("Skipping content-addressed store pruning; lock unavailable {}",
                        lockPath, ex);
                return 0L;
            }
            if (acquired == null) {
                return 0L;
            }
            try (FileLock ignored = acquired) {
                return prunePublishedEntries(pruneRoot, maxBytes);
            }
        } catch (IOException ex) {
            LOG.debug("Skipping content-addressed store pruning; lock unavailable {}",
                    lockPath, ex);
            return 0L;
        }
    }

    private long prunePublishedEntries(Path pruneRoot, long maxBytes) {
        List<StoredFile> files = listStoredFiles(pruneRoot);
        long total = files.stream().mapToLong(StoredFile::size).sum();
        if (total <= maxBytes) {
            return 0L;
        }

        long retained = files.stream().filter(file -> !file.deletable())
                .mapToLong(StoredFile::size).sum();
        if (retained >= maxBytes) {
            LOG.info("Skipping content-addressed store pruning; {} retained bytes"
                    + " already fill the {} byte cap in {}",
                    retained, maxBytes, pruneRoot);
            return 0L;
        }
        long deletableCap = maxBytes - retained;
        long deletableTotal = total - retained;

        files.sort(Comparator.comparing(StoredFile::lastModified)
                .thenComparing(StoredFile::path));
        long removed = 0L;
        for (StoredFile file : files) {
            if (deletableTotal - removed <= deletableCap) {
                break;
            }
            if (file.deletable() && deleteQuietly(file.path())) {
                removed += file.size();
            }
        }
        return removed;
    }

    private List<StoredFile> listStoredFiles(Path pruneRoot) {
        List<StoredFile> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(pruneRoot)) {
            walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(ENTRY_EXTENSION))
                    .forEach(path -> {
                        try {
                            files.add(new StoredFile(path, Files.size(path),
                                    Files.getLastModifiedTime(path),
                                    !path.startsWith(pruneRoot.resolve(
                                            READER_PACKS_DIRECTORY))));
                        } catch (IOException ex) {
                            // concurrently removed, nothing to account for
                        }
                    });
        } catch (IOException | UncheckedIOException ex) {
            LOG.debug("Failed to walk content-addressed store {}", pruneRoot, ex);
            return List.of();
        }
        return files;
    }

    private Path entryPath(String category, String sha256Hex, String qualifier) {
        requireSafeName(category, "category");
        requireSha256Hex(sha256Hex);
        requireSafeName(qualifier, "qualifier");
        return root.resolve(category)
                .resolve(sha256Hex.substring(0, 2))
                .resolve(sha256Hex + '-' + qualifier + ENTRY_EXTENSION);
    }

    private Path keyedEntryPath(String category, String keyHex, String qualifier) {
        requireSafeName(category, "category");
        requireKeyHex(keyHex);
        requireSafeName(qualifier, "qualifier");
        return root.resolve(category)
                .resolve(keyHex.substring(0, 2))
                .resolve(keyHex + '-' + qualifier + ENTRY_EXTENSION);
    }

    private static void publish(Path temp, Path entry) throws IOException {
        try {
            Files.move(temp, entry, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, entry, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileAlreadyExistsException ex) {
            // a concurrent writer published the identical payload first
            deleteQuietly(temp);
        }
    }

    private static boolean deleteQuietly(Path path) {
        if (path == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(path);
        } catch (IOException ex) {
            return false;
        }
    }

    private static String sha256Hex(byte[] content) {
        return HexFormat.of().formatHex(newSha256().digest(content));
    }

    /**
     * Creates a directory and every missing parent with owner-only access,
     * atomically through the POSIX view where the file system supports it.
     */
    private static void createPrivateDirectories(Path directory) throws IOException {
        Files.createDirectories(directory,
                privateAttributes(directory, OWNER_ONLY_DIRECTORY));
    }

    private static FileAttribute<?>[] privateAttributes(Path path,
            Set<PosixFilePermission> permissions) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? new FileAttribute<?>[] {
                        PosixFilePermissions.asFileAttribute(permissions) }
                : NO_ATTRIBUTES;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static void requireSha256Hex(String sha256Hex) {
        Objects.requireNonNull(sha256Hex, "sha256Hex");
        if (sha256Hex.length() != SHA_256_HEX_LENGTH) {
            throw new IllegalArgumentException(
                    "SHA-256 hex must contain exactly 64 characters");
        }
        requireLowercaseHex(sha256Hex, "SHA-256");
    }

    private static void requireKeyHex(String keyHex) {
        Objects.requireNonNull(keyHex, "keyHex");
        if (keyHex.length() != MD5_HEX_LENGTH && keyHex.length() != SHA_256_HEX_LENGTH) {
            throw new IllegalArgumentException(
                    "External key hex must contain exactly 32 or 64 characters");
        }
        requireLowercaseHex(keyHex, "External key");
    }

    private static void requireKeyedPayloadLimit(int maxPayloadBytes) {
        if (maxPayloadBytes <= 0 || maxPayloadBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Keyed payload cap must be positive and smaller than Integer.MAX_VALUE");
        }
    }

    private static void requireLowercaseHex(String hex, String role) {
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                throw new IllegalArgumentException(
                        role + " hex must be lowercase hexadecimal: " + hex);
            }
        }
    }

    private static void requireSafeName(String name, String role) {
        Objects.requireNonNull(name, role);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Store " + role + " must not be empty");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean safe = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || c == '_' || c == '-' || c == '.';
            if (!safe) {
                throw new IllegalArgumentException(
                        "Store " + role + " contains unsafe character: " + name);
            }
        }
    }

    private record StoredFile(Path path, long size, FileTime lastModified,
            boolean deletable) {
    }
}
