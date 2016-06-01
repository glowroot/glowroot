/*
 * Copyright 2011-2016 the original author or authors.
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

import java.util.List;

import javax.annotation.Nullable;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    public String getPermission() {
        return "agent:view:trace";
    }

    @Override
    public @Nullable FullHttpResponse handleRequest(ChannelHandlerContext ctx, HttpRequest request)
            throws Exception {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        String path = decoder.path();
        String traceComponent = path.substring(path.lastIndexOf('/') + 1);
        List<String> agentIds = decoder.parameters().get("agent-id");
        checkNotNull(agentIds, "Missing agent id in query string: %s", request.uri());
        String agentId = agentIds.get(0);
        List<String> traceIds = decoder.parameters().get("trace-id");
        checkNotNull(traceIds, "Missing trace id in query string: %s", request.uri());
        String traceId = traceIds.get(0);
        // check-live-traces is an optimization so glowroot server only has to check with remote
        // agents when necessary
        List<String> checkLiveTracesParams = decoder.parameters().get("check-live-traces");
        boolean checkLiveTraces = false;
        if (checkLiveTracesParams != null && !checkLiveTracesParams.isEmpty()) {
            checkLiveTraces = Boolean.parseBoolean(checkLiveTracesParams.get(0));
        }
        logger.debug(
                "handleRequest(): traceComponent={}, agentId={}, traceId={}, checkLiveTraces={}",
                traceComponent, agentId, traceId, checkLiveTraces);

        ChunkSource detail =
                getDetailChunkSource(traceComponent, agentId, traceId, checkLiveTraces);
        if (detail == null) {
            return new DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive && !request.protocolVersion().isKeepAliveDefault()) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
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

    private @Nullable ChunkSource getDetailChunkSource(String traceComponent, String agentId,
            String traceId, boolean checkLiveTraces) throws Exception {
        if (traceComponent.equals("entries")) {
            String entriesJson =
                    traceCommonService.getEntriesJson(agentId, traceId, checkLiveTraces);
            if (entriesJson == null) {
                // this includes trace was found but the trace had no trace entries
                // caller should check trace.entry_count
                return null;
            }
            return ChunkSource.wrap(entriesJson);
        }
        if (traceComponent.equals("main-thread-profile")) {
            String profileJson = traceCommonService.getMainThreadProfileJson(agentId, traceId,
                    checkLiveTraces);
            if (profileJson == null) {
                return null;
            }
            return ChunkSource.wrap(profileJson);
        }
        if (traceComponent.equals("aux-thread-profile")) {
            String profileJson = traceCommonService.getAuxThreadProfileJson(agentId, traceId,
                    checkLiveTraces);
            if (profileJson == null) {
                return null;
            }
            return ChunkSource.wrap(profileJson);
        }
        throw new IllegalStateException("Unexpected trace component: " + traceComponent);
    }
}
