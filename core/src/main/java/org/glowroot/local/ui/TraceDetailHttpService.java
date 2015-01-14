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
package org.glowroot.local.ui;

import java.io.StringReader;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.io.CharSource;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders.Names;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

class TraceDetailHttpService implements HttpService {

    private static final Logger logger = LoggerFactory.getLogger(TraceDetailHttpService.class);

    private final TraceCommonService traceCommonService;

    TraceDetailHttpService(TraceCommonService traceCommonService) {
        this.traceCommonService = traceCommonService;
    }

    @Override
    public @Nullable HttpResponse handleRequest(HttpRequest request, Channel channel)
            throws Exception {
        QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());
        String path = decoder.getPath();
        String traceComponent = path.substring(path.lastIndexOf('/') + 1);
        List<String> traceIds = decoder.getParameters().get("trace-id");
        checkNotNull(traceIds, "Missing trace id in query string: %s", request.getUri());
        String traceId = traceIds.get(0);
        logger.debug("handleRequest(): traceComponent={}, traceId={}", traceComponent, traceId);

        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.headers().set(Names.CONTENT_TYPE, "application/json; charset=UTF-8");
        CharSource charSource = getDetailCharSource(traceComponent, traceId);
        HttpServices.preventCaching(response);
        response.setChunked(true);
        channel.write(response);
        if (charSource == null) {
            // UI checks entriesExistence/profileExistence so should not end up here
            channel.write(ChunkedInputs.fromReader(new StringReader("null")));
        } else {
            channel.write(ChunkedInputs.fromReader(charSource.openStream()));
        }
        return null;
    }

    private @Nullable CharSource getDetailCharSource(String traceComponent, String traceId)
            throws Exception {
        if (traceComponent.equals("entries")) {
            return traceCommonService.getEntries(traceId);
        }
        if (traceComponent.equals("profile")) {
            return traceCommonService.getProfile(traceId);
        }
        throw new IllegalStateException("Unexpected trace component: " + traceComponent);
    }
}
