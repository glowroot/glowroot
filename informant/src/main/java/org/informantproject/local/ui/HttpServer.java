/**
 * Copyright 2011-2012 the original author or authors.
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
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.informantproject.util.HttpServerBase;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders.Names;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;
import com.google.gson.Gson;
import com.google.inject.BindingAnnotation;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Handles all http requests for the embedded UI (by default http://localhost:4000).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class HttpServer extends HttpServerBase {

    private static final Logger logger = LoggerFactory.getLogger(HttpServer.class);

    private final Map<Pattern, Object> uriMappings = Collections
            .synchronizedMap(new LinkedHashMap<Pattern, Object>());

    {
        uriMappings.put(Pattern.compile("^/resources/jquery/(.*)$"),
                "org/informantproject/javascript/jquery/$1");
        uriMappings.put(Pattern.compile("^/resources/jqueryui/(.*)$"),
                "org/informantproject/javascript/jqueryui/$1");
        uriMappings.put(Pattern.compile("^/resources/flot/(.*)$"),
                "org/informantproject/javascript/flot/$1");
        uriMappings.put(Pattern.compile("^/resources/dynatree/(.*)$"),
                "org/informantproject/javascript/dynatree/$1");
        uriMappings.put(Pattern.compile("^/resources/dateformat/(.*)$"),
                "org/informantproject/javascript/dateformat/$1");
        uriMappings.put(Pattern.compile("^/resources/handlebars/(.*)$"),
                "org/informantproject/javascript/handlebars/$1");

        uriMappings.put(Pattern.compile("^/resources/javascript/(.*)$"),
                "org/informantproject/local/ui/javascript/$1");
        uriMappings.put(Pattern.compile("^/resources/css/(.*)$"),
                "org/informantproject/local/ui/css/$1");
        uriMappings.put(Pattern.compile("^/traces.html$"),
                "org/informantproject/local/ui/traces.html");
        uriMappings.put(Pattern.compile("^/metrics.html$"),
                "org/informantproject/local/ui/metrics.html");
        uriMappings.put(Pattern.compile("^/configuration.html$"),
                "org/informantproject/local/ui/configuration.html");
    }

    @Inject
    public HttpServer(@LocalHttpServerPort int port,
            ReadConfigurationJsonService readConfigurationJsonService,
            UpdateConfigurationJsonService updateConfigurationJsonService,
            TraceDetailJsonService traceDetailJsonService,
            TraceSummaryJsonService traceSummaryJsonService,
            MetricJsonService metricJsonService) {

        super(port, "Informant-");
        uriMappings.put(Pattern.compile("^/configuration/read$"), readConfigurationJsonService);
        uriMappings.put(Pattern.compile("^/configuration/update$"), updateConfigurationJsonService);
        uriMappings.put(Pattern.compile("^/trace/details$"), traceDetailJsonService);
        uriMappings.put(Pattern.compile("^/trace/summaries$"), traceSummaryJsonService);
        uriMappings.put(Pattern.compile("^/metrics$"), metricJsonService);
    }

    @Override
    public HttpResponse handleRequest(HttpRequest request) throws IOException {
        logger.debug("handleRequest(): request.uri={}", request.getUri());
        QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());
        String path = decoder.getPath();
        logger.debug("handleRequest(): path={}", path);
        for (Entry<Pattern, Object> uriMappingEntry : uriMappings.entrySet()) {
            Matcher matcher = uriMappingEntry.getKey().matcher(path);
            if (matcher.matches()) {
                if (uriMappingEntry.getValue() instanceof JsonService) {
                    return handleJsonRequest(request, decoder,
                            (JsonService) uriMappingEntry.getValue());
                } else {
                    // only other value type is String
                    String resourcePath = matcher.replaceFirst((String) uriMappingEntry.getValue());
                    return handleStaticRequest(resourcePath);
                }
            }
        }
        logger.warn("Unexpected uri '{}'", request.getUri());
        return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
    }

    private static HttpResponse handleStaticRequest(String path) throws IOException {
        int extensionStartIndex = path.lastIndexOf(".");
        if (extensionStartIndex == -1) {
            logger.warn("Missing extension '{}'", path);
            return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        String extension = path.substring(extensionStartIndex + 1);
        String mimeType = getMimeType(extension);
        if (mimeType == null) {
            logger.warn("Unexpected extension '{}' for path '{}'", extension, path);
            return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        InputStream staticContentStream = HttpServer.class.getClassLoader().getResourceAsStream(
                path);
        if (staticContentStream == null) {
            logger.warn("Unexpected path '{}'", path);
            return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
        }
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

    private static String getMimeType(String extension) {
        if (extension.equals("html")) {
            return "text/html";
        } else if (extension.equals("js")) {
            return "text/javascript";
        } else if (extension.equals("css")) {
            return "text/css";
        } else if (extension.equals("png")) {
            return "image/png";
        } else {
            return null;
        }
    }

    private static HttpResponse handleJsonRequest(HttpRequest request, QueryStringDecoder decoder,
            JsonService jsonService) throws IOException {

        String requestText = getRequestJson(request, decoder);
        logger.debug("handleJsonRequest(): request.content={}", requestText);
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

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @BindingAnnotation
    public @interface LocalHttpServerPort {}
}
