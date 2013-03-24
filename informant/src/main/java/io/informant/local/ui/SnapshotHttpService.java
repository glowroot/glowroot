/**
 * Copyright 2011-2013 the original author or authors.
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

import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.informant.markers.Singleton;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;

import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders.Names;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.stream.ChunkedInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.nullness.quals.Nullable;

import com.google.common.base.Charsets;
import com.google.common.io.CharSource;

/**
 * Http service to read trace snapshot data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class SnapshotHttpService implements HttpService {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotHttpService.class);

    private final TraceCommonService traceCommonService;

    SnapshotHttpService(TraceCommonService traceCommonService) {
        this.traceCommonService = traceCommonService;
    }

    @Nullable
    public HttpResponse handleRequest(HttpRequest request, Channel channel) throws IOException {
        String uri = request.getUri();
        String id = uri.substring(uri.lastIndexOf('/') + 1);
        logger.debug("handleRequest(): id={}", id);
        CharSource charSource =
                traceCommonService.createCharSourceForSnapshotOrActiveTrace(id, false);
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.setHeader(Names.CONTENT_TYPE, "application/json; charset=UTF-8");
        if (charSource == null) {
            logger.debug("no trace found for id '{}', returning expired=true", id);
            String content = "{\"expired\":true}";
            response.setContent(ChannelBuffers.copiedBuffer(content, Charsets.UTF_8));
            response.setHeader(Names.CONTENT_LENGTH, content.length());
            return response;
        }
        HttpServices.preventCaching(response);
        response.setChunked(true);
        channel.write(response);
        channel.write(new ReaderChunkedInput(charSource.openStream()));
        // return null to indicate streaming
        return null;
    }

    private static class ReaderChunkedInput implements ChunkedInput {

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
}
