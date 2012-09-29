/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.core.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.stream.ChunkedInput;

import com.google.common.base.Charsets;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public abstract class ByteStream {

    private static final Logger logger = LoggerFactory.getLogger(ByteStream.class);

    protected ByteStream() {}

    public abstract boolean hasNext();

    public abstract byte[] next() throws IOException;

    public ChunkedInput toChunkedInput() {
        return new ByteStreamChunkedInput(this);
    }

    public void writeTo(OutputStream out) throws IOException {
        while (hasNext()) {
            out.write(next());
        }
    }

    public static ByteStream of(byte[] bytes) {
        return new ByteStreamOfOne(bytes);
    }

    public static ByteStream of(String s) {
        try {
            return new ByteStreamOfOne(s.getBytes(Charsets.UTF_8.name()));
        } catch (UnsupportedEncodingException e) {
            // this is impossible (UTF_8 is always supported)
            logger.error(e.getMessage(), e);
            return new ByteStreamOfOne(s.getBytes());
        }
    }

    public static ByteStream of(List<ByteStream> byteStreams) {
        return new ConcatByteStream(byteStreams.iterator());
    }

    private static class ByteStreamOfOne extends ByteStream {

        private final byte[] bytes;
        private boolean end;

        private ByteStreamOfOne(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public boolean hasNext() {
            return !end;
        }

        @Override
        public byte[] next() {
            end = true;
            return bytes;
        }
    }

    private static class ConcatByteStream extends ByteStream {

        private final Iterator<ByteStream> byteStreams;
        private ByteStream curr = new EmptyByteStream();

        private ConcatByteStream(Iterator<ByteStream> byteStreams) {
            this.byteStreams = byteStreams;
        }

        @Override
        public boolean hasNext() {
            while (!curr.hasNext() && byteStreams.hasNext()) {
                curr = byteStreams.next();
            }
            return curr.hasNext();
        }

        @Override
        public byte[] next() throws IOException {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return curr.next();
        }
    }

    private static class EmptyByteStream extends ByteStream {

        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public byte[] next() throws IOException {
            throw new NoSuchElementException();
        }
    }

    private static class ByteStreamChunkedInput implements ChunkedInput {

        private final ByteStream byteStream;
        private boolean hasNotSentTerminatingChunk = true;

        private ByteStreamChunkedInput(ByteStream byteStream) {
            this.byteStream = byteStream;
        }

        public boolean hasNextChunk() {
            return hasNotSentTerminatingChunk;
        }

        public Object nextChunk() throws IOException {
            if (byteStream.hasNext()) {
                return new DefaultHttpChunk(ChannelBuffers.wrappedBuffer(byteStream.next()));
            } else if (hasNotSentTerminatingChunk) {
                hasNotSentTerminatingChunk = false;
                return new DefaultHttpChunk(ChannelBuffers.EMPTY_BUFFER);
            } else {
                return null;
            }
        }

        public boolean isEndOfInput() {
            return !hasNextChunk();
        }

        public void close() {}
    }
}
