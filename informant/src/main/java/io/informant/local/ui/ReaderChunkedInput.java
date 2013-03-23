/**
 * Copyright 2013 the original author or authors.
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
package io.informant.local.ui;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;

import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.stream.ChunkedInput;

import checkers.nullness.quals.Nullable;

import com.google.common.base.Charsets;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class ReaderChunkedInput implements ChunkedInput {

    private final PushbackReader reader;
    private final char[] buffer = new char[8192];

    private boolean hasSentTerminatingChunk;

    ReaderChunkedInput(Reader reader) {
        this.reader = new PushbackReader(reader);
    }

    public boolean hasNextChunk() throws Exception {
        return !hasSentTerminatingChunk;
    }

    @Nullable
    public Object nextChunk() throws Exception {
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

    public boolean isEndOfInput() throws Exception {
        return hasSentTerminatingChunk;
    }

    public void close() throws Exception {
        reader.close();
    }

    private boolean hasMoreBytes() throws IOException {
        int b = reader.read();
        if (b == -1) {
            return false;
        } else {
            reader.unread(b);
            return true;
        }
    }

    private Object readNextChunk() throws IOException {
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
        return new DefaultHttpChunk(ChannelBuffers.copiedBuffer(buffer, 0, total,
                Charsets.UTF_8));
    }
}
