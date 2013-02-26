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
import io.informant.util.ByteStream;
import io.informant.util.Singleton;

import java.io.IOException;

import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpHeaders.Names;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.nullness.quals.Nullable;

import com.google.common.base.Charsets;

/**
 * Http service to read trace snapshot data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class TraceSnapshotHttpService implements HttpService {

    private static final Logger logger = LoggerFactory.getLogger(TraceSnapshotHttpService.class);

    private final TraceCommonService traceCommonService;

    TraceSnapshotHttpService(TraceCommonService traceCommonService) {
        this.traceCommonService = traceCommonService;
    }

    @Nullable
    public HttpResponse handleRequest(HttpRequest request, Channel channel) throws IOException {
        String uri = request.getUri();
        String id = uri.substring(uri.lastIndexOf('/') + 1);
        logger.debug("handleRequest(): id={}", id);
        ByteStream byteStream = traceCommonService.getSnapshotOrActiveJson(id, false);
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.setHeader(Names.CONTENT_TYPE, "application/json; charset=UTF-8");
        if (byteStream == null) {
            logger.debug("no trace found for id '{}', returning expired=true", id);
            String content = "{\"expired\":true}";
            response.setContent(ChannelBuffers.copiedBuffer(content, Charsets.UTF_8));
            response.setHeader(Names.CONTENT_LENGTH, content.length());
            return response;
        }
        if (HttpHeaders.isKeepAlive(request)) {
            // keep alive is not supported to avoid having to calculate content length
            response.setHeader(Names.CONNECTION, "close");
        }
        HttpServices.preventCaching(response);
        response.setChunked(true);
        channel.write(response);
        ChannelFuture f = channel.write(byteStream.toChunkedInput());
        f.addListener(ChannelFutureListener.CLOSE);
        // return null to indicate streaming
        return null;
    }
}
