/*
 * Copyright 2013-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.local.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.Writer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.common.base.Charsets;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.stream.ChunkedInput;

class ChunkedInputs {

    static ChunkedInput fromReader(Reader reader) {
        return new ReaderChunkedInput(reader);
    }

    static ChunkedInput fromReaderToZipFileDownload(Reader reader, String filename)
            throws IOException {
        return new ZipFileChunkedInput(reader, filename);
    }

    private ChunkedInputs() {}

    private abstract static class BaseChunkedInput implements ChunkedInput {

        private boolean hasSentTerminatingChunk;

        @Override
        public boolean hasNextChunk() {
            return !hasSentTerminatingChunk;
        }

        @Override
        @Nullable
        public Object nextChunk() throws IOException {
            if (hasMoreBytes()) {
                return readNextChunk();
            } else if (!hasSentTerminatingChunk) {
                // chunked transfer encoding must be terminated by a final chunk of length zero
                hasSentTerminatingChunk = true;
                return new DefaultHttpChunk(ChannelBuffers.EMPTY_BUFFER);
            } else {
                return null;
            }
        }

        @Override
        public boolean isEndOfInput() {
            return hasSentTerminatingChunk;
        }

        protected abstract boolean hasMoreBytes() throws IOException;

        protected abstract Object readNextChunk() throws IOException;

        private static boolean hasMoreBytes(PushbackReader reader) throws IOException {
            int b = reader.read();
            if (b == -1) {
                return false;
            } else {
                reader.unread(b);
                return true;
            }
        }

        private static int readFully(Reader reader, char[] buffer) throws IOException {
            int total = 0;
            while (true) {
                int n = reader.read(buffer, total, buffer.length - total);
                if (n == -1) {
                    break;
                }
                total += n;
                if (total == buffer.length) {
                    break;
                }
            }
            return total;
        }
    }

    private static class ReaderChunkedInput extends BaseChunkedInput {

        private static final int CHUNK_SIZE = 8192;

        private final PushbackReader reader;
        private final char[] buffer = new char[CHUNK_SIZE];

        private ReaderChunkedInput(Reader reader) {
            this.reader = new PushbackReader(reader);
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }

        @Override
        protected boolean hasMoreBytes() throws IOException {
            return BaseChunkedInput.hasMoreBytes(reader);
        }

        @Override
        protected Object readNextChunk() throws IOException {
            int len = BaseChunkedInput.readFully(reader, buffer);
            return new DefaultHttpChunk(ChannelBuffers.copiedBuffer(buffer, 0, len,
                    Charsets.UTF_8));
        }
    }

    private static class ZipFileChunkedInput extends BaseChunkedInput {

        private static final int CHUNK_SIZE = 8192;

        private final PushbackReader reader;
        private final ByteArrayOutputStream baos;
        private final Writer zipWriter;
        // need lots more chars to end up with compressed chunk of given size
        private final char[] buffer = new char[8 * CHUNK_SIZE];

        private ZipFileChunkedInput(Reader reader, String filename) throws IOException {
            this.reader = new PushbackReader(reader);
            // write to baos until size >= CHUNK_SIZE, so give it a little extra room
            baos = new ByteArrayOutputStream(2 * CHUNK_SIZE);
            ZipOutputStream zipOut = new ZipOutputStream(baos);
            zipOut.putNextEntry(new ZipEntry(filename + ".html"));
            zipWriter = new OutputStreamWriter(zipOut, Charsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }

        @Override
        protected boolean hasMoreBytes() throws IOException {
            return BaseChunkedInput.hasMoreBytes(reader);
        }

        @Override
        protected Object readNextChunk() throws IOException {
            int len = BaseChunkedInput.readFully(reader, buffer);
            // no need to flush, there's no buffering except in ZipOutputStream, and that buffering
            // is for compression and doesn't respond to flush() anyways
            zipWriter.write(buffer, 0, len);
            if (baos.size() < CHUNK_SIZE && hasMoreBytes()) {
                return readNextChunk();
            }
            if (!hasMoreBytes()) {
                // write remaining compressed data
                zipWriter.close();
            }
            // toByteArray returns a copy so it's ok to reset the ByteArrayOutputStream afterwards
            byte[] bytes = baos.toByteArray();
            baos.reset();
            return new DefaultHttpChunk(ChannelBuffers.wrappedBuffer(bytes));
        }
    }
}
