/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject.local.ui;

import static org.jboss.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders.Names;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.informantproject.util.SimpleHttpServer.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;
import com.google.gson.Gson;

/**
 * Handles all http requests for the embedded UI (by default http://localhost:4000).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class LocalHttpHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(LocalHttpHandler.class);

    private final Map<String, JsonService> jsonServiceMap;

    public LocalHttpHandler(Map<String, JsonService> jsonServiceMap) {
        this.jsonServiceMap = jsonServiceMap;
    }

    public HttpResponse handleRequest(HttpRequest request) throws IOException {
        logger.debug("messageReceived(): request.uri={}", request.getUri());
        if (request.getUri().equals("/")) {
            return getResponseForStaticContent("Main.html", "text/html");
        } else if (request.getUri().startsWith("/js/")) {
            String path = request.getUri().substring("/js/".length());
            return getResponseForStaticContent(path, "text/javascript");
        } else if (request.getUri().startsWith("/css/")) {
            String path = request.getUri().substring("/css/".length());
            return getResponseForStaticContent(path, "text/css");
        }
        QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());
        logger.debug("messageReceived(): path={}", decoder.getPath());
        JsonService jsonService = jsonServiceMap.get(decoder.getPath());
        if (jsonService == null) {
            DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
            response.setContent(ChannelBuffers.EMPTY_BUFFER);
            return response;
        } else {
            String requestText = getRequestJson(request, decoder);
            logger.debug("messageReceived(): request.content={}", requestText);
            String responseText = jsonService.handleRequest(requestText);
            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
            if (responseText == null) {
                response.setContent(ChannelBuffers.EMPTY_BUFFER);
            } else {
                response.setContent(ChannelBuffers.copiedBuffer(responseText, Charsets.ISO_8859_1));
                response.setHeader(Names.CONTENT_TYPE, "application/json; charset=UTF-8");
            }
            return response;
        }
    }

    private static HttpResponse getResponseForStaticContent(String resourcePath, String mimeType)
            throws IOException {

        InputStream staticContentStream = LocalHttpHandler.class.getResourceAsStream(resourcePath);
        byte[] staticContent;
        try {
            staticContent = ByteStreams.toByteArray(staticContentStream);
        } finally {
            Closeables.closeQuietly(staticContentStream);
        }
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.setContent(ChannelBuffers.copiedBuffer(staticContent));
        response.setHeader(Names.CONTENT_TYPE, mimeType + "; charset=UTF-8");
        response.setHeader(Names.CONTENT_LENGTH, staticContent.length);
        return response;
    }

    private static String getRequestJson(HttpRequest request, QueryStringDecoder decoder) {

        if (decoder.getParameters().isEmpty()) {
            return request.getContent().toString(Charsets.ISO_8859_1);
        } else {
            // create json message out of the query string
            // flatten map values from list to single element where possible
            Map<String, Object> parameters = new HashMap<String, Object>();
            for (Entry<String, List<String>> entry : decoder.getParameters().entrySet()) {
                if (entry.getValue().size() == 1) {
                    parameters.put(entry.getKey(), entry.getValue().get(0));
                } else {
                    parameters.put(entry.getKey(), entry.getValue());
                }
            }
            return new Gson().toJson(parameters);
        }
    }

    public interface JsonService {
        String handleRequest(String message) throws IOException;
    }
}
