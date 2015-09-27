/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.server.ui;

import java.util.List;

import javax.annotation.Nullable;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpHeaders.Names;
import io.netty.handler.codec.http.HttpHeaders.Values;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.ChunkSource;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

class TraceDetailHttpService implements HttpService {

    private static final Logger logger = LoggerFactory.getLogger(TraceDetailHttpService.class);

    private final TraceCommonService traceCommonService;

    TraceDetailHttpService(TraceCommonService traceCommonService) {
        this.traceCommonService = traceCommonService;
    }

    @Override
    public @Nullable FullHttpResponse handleRequest(ChannelHandlerContext ctx, HttpRequest request)
            throws Exception {
        QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());
        String path = decoder.path();
        String traceComponent = path.substring(path.lastIndexOf('/') + 1);
        List<String> serverIds = decoder.parameters().get("server-id");
        checkNotNull(serverIds, "Missing server id in query string: %s", request.getUri());
        long serverId = Long.parseLong(serverIds.get(0));
        List<String> traceIds = decoder.parameters().get("trace-id");
        checkNotNull(traceIds, "Missing trace id in query string: %s", request.getUri());
        String traceId = traceIds.get(0);
        logger.debug("handleRequest(): traceComponent={}, serverId={}, traceId={}", traceComponent,
                serverId, traceId);

        ChunkSource detail = getDetailChunkSource(traceComponent, serverId, traceId);
        if (detail == null) {
            return new DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.headers().set(Names.TRANSFER_ENCODING, Values.CHUNKED);
        response.headers().set(Names.CONTENT_TYPE, "application/json; charset=UTF-8");
        boolean keepAlive = HttpHeaders.isKeepAlive(request);
        if (keepAlive && !request.getProtocolVersion().isKeepAliveDefault()) {
            response.headers().set(Names.CONNECTION, Values.KEEP_ALIVE);
        }
        HttpServices.preventCaching(response);
        ctx.write(response);
        // TODO no more point in chunking here
        ChannelFuture future = ctx.write(ChunkedInputs.from(detail));
        HttpServices.addErrorListener(future);
        if (!keepAlive) {
            HttpServices.addCloseListener(future);
        }
        // return null to indicate streaming
        return null;
    }

    private @Nullable ChunkSource getDetailChunkSource(String traceComponent, long serverId,
            String traceId) throws Exception {
        if (traceComponent.equals("entries")) {
            String entriesJson = traceCommonService.getEntriesJson(serverId, traceId);
            if (entriesJson == null) {
                // this includes trace was found but the trace had no trace entries
                // caller should check trace.entry_count
                return null;
            }
            return ChunkSource.wrap(entriesJson);
        }
        if (traceComponent.equals("profile")) {
            String profileJson = traceCommonService.getProfileTreeJson(serverId, traceId);
            if (profileJson == null) {
                return null;
            }
            return ChunkSource.wrap(profileJson);
        }
        throw new IllegalStateException("Unexpected trace component: " + traceComponent);
    }
}
