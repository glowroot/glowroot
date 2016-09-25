/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.ui;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.annotation.Nullable;

import com.google.common.base.Charsets;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedInput;

import org.glowroot.ui.ChunkSource.ChunkCopier;

class ChunkedInputs {

    static ChunkedInput<HttpContent> create(ChunkSource chunkSource) throws IOException {
        return new ChunkSourceChunkedInput(chunkSource);
    }

    static ChunkedInput<HttpContent> createZipFileDownload(ChunkSource chunkSource, String fileName)
            throws IOException {
        return new ZipFileChunkedInput(chunkSource, fileName);
    }

    private ChunkedInputs() {}

    private abstract static class BaseChunkedInput implements ChunkedInput<HttpContent> {

        private boolean hasSentTerminatingChunk;

        @Override
        public @Nullable HttpContent readChunk(ChannelHandlerContext ctx) throws Exception {
            return readChunk(ctx.alloc());
        }

        @Override
        public @Nullable HttpContent readChunk(ByteBufAllocator allocator) throws Exception {
            if (hasSentTerminatingChunk) {
                return null;
            }
            ByteBuf nextChunk = readNextChunk();
            if (nextChunk != null) {
                return new DefaultHttpContent(nextChunk);
            }
            // chunked transfer encoding must be terminated by a final chunk of length zero
            hasSentTerminatingChunk = true;
            return LastHttpContent.EMPTY_LAST_CONTENT;
        }

        @Override
        public boolean isEndOfInput() {
            return hasSentTerminatingChunk;
        }

        @Override
        public void close() throws IOException {}

        @Override
        public long length() {
            // negative value means unknown
            return -1;
        }

        @Override
        public long progress() {
            return -1;
        }

        protected abstract @Nullable ByteBuf readNextChunk() throws IOException;
    }

    private static class ChunkSourceChunkedInput extends BaseChunkedInput {

        private final ByteBuf byteBuf;
        private final Writer writer;
        private final ChunkCopier chunkCopier;

        private boolean closed;

        private ChunkSourceChunkedInput(ChunkSource chunkSource) throws IOException {
            byteBuf = Unpooled.buffer();
            writer = new OutputStreamWriter(new ByteBufOutputStream(byteBuf), Charsets.UTF_8);
            chunkCopier = chunkSource.getCopier(writer);
        }

        @Override
        public @Nullable ByteBuf readNextChunk() throws IOException {
            if (closed) {
                return null;
            }
            if (byteBuf.refCnt() > 1) {
                throw new IOException("ByteBuf is still in use by another");
            }
            byteBuf.clear();
            if (chunkCopier.copyNext()) {
                // flush to byteBuf
                writer.flush();
                while (byteBuf.writerIndex() == 0) {
                    chunkCopier.copyNext();
                    // flush to byteBuf
                    writer.flush();
                }
                // increment retain count since still using byteBuf
                byteBuf.retain();
                return byteBuf;
            }
            closed = true;
            return null;
        }
    }

    private static class ZipFileChunkedInput extends BaseChunkedInput {

        private final ByteBuf byteBuf;
        private final ByteBufOutputStream bbos;
        private final Writer zipWriter;
        private final ChunkCopier chunkCopier;

        private boolean firstChunk = true;
        private boolean closed;

        private ZipFileChunkedInput(ChunkSource chunkSource, String fileName) throws IOException {
            byteBuf = Unpooled.buffer();
            bbos = new ByteBufOutputStream(byteBuf);
            ZipOutputStream zipOut = new ZipOutputStream(bbos);
            zipOut.putNextEntry(new ZipEntry(fileName + ".html"));
            zipWriter = new OutputStreamWriter(zipOut, Charsets.UTF_8);
            chunkCopier = chunkSource.getCopier(zipWriter);
        }

        @Override
        protected @Nullable ByteBuf readNextChunk() throws IOException {
            if (closed) {
                return null;
            }
            if (!firstChunk) {
                // don't clear before the first chunk since byteBuf contains zip header
                byteBuf.clear();
            }
            firstChunk = false;
            while (true) {
                if (!chunkCopier.copyNext()) {
                    // write remaining compressed data
                    zipWriter.close();
                    closed = true;
                    return byteBuf;
                }
                if (byteBuf.writerIndex() > 0) {
                    // flush to byteBuf
                    zipWriter.flush();
                    // increment retain count since still using byteBuf
                    byteBuf.retain();
                    return byteBuf;
                }
            }
        }
    }
}
