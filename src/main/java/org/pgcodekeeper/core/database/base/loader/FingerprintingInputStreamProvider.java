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
package org.pgcodekeeper.core.database.base.loader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.function.Consumer;

import org.pgcodekeeper.core.database.api.loader.ProjectInputFingerprint;
import org.pgcodekeeper.core.utils.InputStreamProvider;

final class FingerprintingInputStreamProvider
        implements InputStreamProvider {

    private record Capture(Path path,
            Consumer<ProjectInputFingerprint> sink) {

        private Capture {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(sink, "sink");
        }
    }

    private final InputStreamProvider delegate;
    private volatile Capture capture;

    FingerprintingInputStreamProvider(
            InputStreamProvider delegate) {
        this.delegate = Objects.requireNonNull(
                delegate, "delegate");
    }

    void capture(Path path,
            Consumer<ProjectInputFingerprint> sink) {
        capture = new Capture(path, sink);
    }

    @Override
    public InputStream getStream() throws IOException {
        InputStream stream = delegate.getStream();
        Capture current = capture;
        if (current == null) {
            return stream;
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            try {
                stream.close();
            } catch (IOException suppressed) {
                ex.addSuppressed(suppressed);
            }
            throw new IllegalStateException(
                    "SHA-256 is unavailable", ex);
        }
        return new FingerprintingInputStream(
                stream, digest, current);
    }

    private static final class FingerprintingInputStream
            extends InputStream {

        private static final int SKIP_BUFFER_BYTES = 8 << 10;

        private final InputStream delegate;
        private final MessageDigest digest;
        private final Capture capture;
        private long byteCount;
        private boolean completed;

        private FingerprintingInputStream(
                InputStream delegate, MessageDigest digest,
                Capture capture) {
            this.delegate = delegate;
            this.digest = digest;
            this.capture = capture;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value < 0) {
                complete();
            } else {
                digest.update((byte) value);
                byteCount++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length)
                throws IOException {
            Objects.checkFromIndexSize(
                    offset, length, buffer.length);
            int count = delegate.read(buffer, offset, length);
            if (count < 0) {
                complete();
            } else if (count > 0) {
                digest.update(buffer, offset, count);
                byteCount += count;
            }
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            if (count <= 0) {
                return 0;
            }
            byte[] buffer = new byte[(int) Math.min(
                    count, SKIP_BUFFER_BYTES)];
            long skipped = 0;
            while (skipped < count) {
                int read = read(buffer, 0,
                        (int) Math.min(buffer.length,
                                count - skipped));
                if (read < 0) {
                    break;
                }
                skipped += read;
            }
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        private void complete() {
            if (completed) {
                return;
            }
            completed = true;
            capture.sink().accept(
                    new ProjectInputFingerprint(
                            capture.path(), byteCount,
                            digest.digest()));
        }
    }
}
