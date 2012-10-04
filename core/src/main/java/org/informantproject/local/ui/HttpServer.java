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

import static org.jboss.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.core.util.HttpServerBase;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders.Names;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

/**
 * Handles all http requests for the embedded UI (by default http://localhost:4000).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class HttpServer extends HttpServerBase {

    private static final Logger logger = LoggerFactory.getLogger(HttpServer.class);

    private static final long TEN_YEARS = 10 * 365 * 24 * 60 * 60 * 1000L;

    private final ImmutableMap<Pattern, Object> uriMappings;
    private final ImmutableList<JsonServiceMapping> jsonServiceMappings;
    private final Gson gson = new Gson();

    @Inject
    HttpServer(@Named("ui.port") int port, TracePointJsonService tracePointJsonService,
            TraceSummaryJsonService traceSummaryJsonService,
            TraceDetailHttpService traceDetailHttpService,
            TraceExportHttpService traceExportHttpService, ConfigJsonService configJsonService,
            MiscJsonService miscJsonService) {

        super(port);
        ImmutableMap.Builder<Pattern, Object> uriMappings = ImmutableMap.builder();
        // pages
        uriMappings.put(Pattern.compile("^/$"), "org/informantproject/local/ui/index.html");
        uriMappings.put(Pattern.compile("^/traces.html$"),
                "org/informantproject/local/ui/traces.html");
        uriMappings.put(Pattern.compile("^/configuration.html$"),
                "org/informantproject/local/ui/configuration.html");
        uriMappings.put(Pattern.compile("^/misc.html$"), "org/informantproject/local/ui/misc.html");
        uriMappings.put(Pattern.compile("^/threaddump.html$"),
                "org/informantproject/local/ui/threaddump.html");
        uriMappings.put(Pattern.compile("^/log.html$"),
                "org/informantproject/local/ui/log.html");
        // internal resources
        uriMappings.put(Pattern.compile("^/img/(.*)$"), "org/informantproject/local/ui/img/$1");
        uriMappings.put(Pattern.compile("^/css/(.*)$"), "org/informantproject/local/ui/css/$1");
        uriMappings.put(Pattern.compile("^/js/(.*)$"), "org/informantproject/local/ui/js/$1");
        uriMappings.put(Pattern.compile("^/libs/(.*)$"), "org/informantproject/local/ui/libs/$1");
        // services
        uriMappings.put(Pattern.compile("^/trace/export/.*$"), traceExportHttpService);
        uriMappings.put(Pattern.compile("^/trace/detail/.*$"), traceDetailHttpService);
        this.uriMappings = uriMappings.build();

        // the parentheses define the part of the match that is used to construct the args for
        // calling the method in json service, e.g. /trace/detail/abc123 below calls the method
        // getDetail("abc123") in TraceJsonService
        ImmutableList.Builder<JsonServiceMapping> jsonServiceMappings = ImmutableList.builder();
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/trace/points$"),
                tracePointJsonService, "getPoints"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/trace/summary/(.*)$"),
                traceSummaryJsonService, "getSummary"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/config/read$"),
                configJsonService, "getConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/config/core"),
                configJsonService, "storeCoreConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/config/core/enable"),
                configJsonService, "enableCore"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/config/core/disable"),
                configJsonService, "disableCore"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/config/profiling"
                + "/coarse"), configJsonService, "storeCoarseProfilingConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/config/profiling/coarse"
                + "/enable"), configJsonService, "enableCoarseProfiling"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/config/profiling/coarse"
                + "/disable"), configJsonService, "disableCoarseProfiling"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/config/profiling/fine"),
                configJsonService, "storeFineProfilingConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/config/profiling/fine"
                + "/enable"), configJsonService, "enableFineProfiling"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/config/profiling/fine"
                + "/disable"), configJsonService, "disableFineProfiling"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/config/tracing/user"),
                configJsonService, "storeUserTracingConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/config/tracing/user"
                + "/enable"), configJsonService, "enableUserTracing"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/config/tracing/user"
                + "/disable"), configJsonService, "disableUserTracing"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/config/plugin"
                + "/([^/]+)/enable"), configJsonService, "enablePlugin"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/config/plugin"
                + "/([^/]+)/disable"), configJsonService, "disablePlugin"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/config/plugin"
                + "/([^/]+)$"), configJsonService, "storePluginConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/misc/cleardata$"),
                miscJsonService, "clearData"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/misc"
                + "/numPendingTraceWrites$"), miscJsonService, "getNumPendingTraceWrites"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/misc/dbFile$"),
                miscJsonService, "getDbFilePath"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/misc/threaddump$"),
                miscJsonService, "getThreadDump"));
        jsonServiceMappings.add(new JsonServiceMapping(Pattern.compile("^/misc/log"),
                miscJsonService, "getLog"));
        this.jsonServiceMappings = jsonServiceMappings.build();
    }

    @Override
    @Nullable
    protected HttpResponse handleRequest(HttpRequest request, Channel channel) throws IOException {
        logger.debug("handleRequest(): request.uri={}", request.getUri());
        QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());
        String path = decoder.getPath();
        logger.debug("handleRequest(): path={}", path);
        for (Entry<Pattern, Object> uriMappingEntry : uriMappings.entrySet()) {
            Matcher matcher = uriMappingEntry.getKey().matcher(path);
            if (matcher.matches()) {
                if (uriMappingEntry.getValue() instanceof HttpService) {
                    return ((HttpService) uriMappingEntry.getValue()).handleRequest(request,
                            channel);
                } else {
                    // only other value type is String
                    String resourcePath = matcher.replaceFirst((String) uriMappingEntry.getValue());
                    return handleStaticRequest(resourcePath);
                }
            }
        }
        for (JsonServiceMapping jsonServiceMapping : jsonServiceMappings) {
            Matcher matcher = jsonServiceMapping.pattern.matcher(path);
            if (matcher.matches()) {
                String requestText = getRequestText(request, decoder);
                String[] args = new String[matcher.groupCount()];
                for (int i = 0; i < args.length; i++) {
                    args[i] = matcher.group(i + 1);
                }
                return handleJsonRequest(jsonServiceMapping.service, jsonServiceMapping.methodName,
                        args, requestText);
            }
        }
        logger.warn("unexpected uri '{}'", request.getUri());
        return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
    }

    private static HttpResponse handleStaticRequest(String path) throws IOException {
        int extensionStartIndex = path.lastIndexOf('.');
        if (extensionStartIndex == -1) {
            logger.warn("missing extension '{}'", path);
            return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        String extension = path.substring(extensionStartIndex + 1);
        String mimeType = getMimeType(extension);
        if (mimeType == null) {
            logger.warn("unexpected extension '{}' for path '{}'", extension, path);
            return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        URL url = Resources.getResource(path);
        if (url == null) {
            logger.warn("unexpected path '{}'", path);
            return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        byte[] staticContent = ByteStreams.toByteArray(Resources.newInputStreamSupplier(url));
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.setContent(ChannelBuffers.copiedBuffer(staticContent));
        response.setHeader(Names.CONTENT_TYPE, mimeType);
        response.setHeader(Names.CONTENT_LENGTH, staticContent.length);
        if (path.startsWith("org/informantproject/local/ui/libs/")) {
            // these are all third-party versioned resources and can be safely cached forever
            response.setHeader(Names.EXPIRES, new Date(System.currentTimeMillis() + TEN_YEARS));
        }
        return response;
    }

    @Nullable
    private static String getMimeType(String extension) {
        if (extension.equals("html")) {
            return "text/html; charset=UTF-8";
        } else if (extension.equals("js")) {
            return "application/javascript; charset=UTF-8";
        } else if (extension.equals("css")) {
            return "text/css; charset=UTF-8";
        } else if (extension.equals("png")) {
            return "image/png";
        } else if (extension.equals("ico")) {
            return "image/x-icon";
        } else if (extension.equals("woff")) {
            return "application/x-font-woff";
        } else if (extension.equals("eot")) {
            return "application/vnd.ms-fontobject";
        } else if (extension.equals("ttf")) {
            return "application/x-font-ttf";
        } else if (extension.equals("swf")) {
            return "application/x-shockwave-flash";
        } else {
            return null;
        }
    }

    private static HttpResponse handleJsonRequest(JsonService jsonService,
            String serviceMethodName, String[] args, String requestText) {

        logger.debug("handleJsonRequest(): serviceMethodName={}, args={}, requestText={}",
                new Object[] { serviceMethodName, args, requestText });
        Object responseText;
        try {
            responseText = callMethod(jsonService, serviceMethodName, args, requestText);
        } catch (SecurityException e) {
            logger.error(e.getMessage(), e);
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        } catch (IllegalArgumentException e) {
            logger.error(e.getMessage(), e);
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        } catch (NoSuchMethodException e) {
            logger.error(e.getMessage(), e);
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        } catch (IllegalAccessException e) {
            logger.error(e.getMessage(), e);
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        } catch (InvocationTargetException e) {
            logger.error(e.getMessage(), e);
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        }
        HttpResponse response;
        if (responseText == null) {
            response = new DefaultHttpResponse(HTTP_1_1, OK);
            response.setContent(ChannelBuffers.EMPTY_BUFFER);
        } else if (responseText instanceof String) {
            response = new DefaultHttpResponse(HTTP_1_1, OK);
            response.setContent(ChannelBuffers.copiedBuffer(responseText.toString(),
                    Charsets.ISO_8859_1));
            response.setHeader(Names.CONTENT_TYPE, "application/json; charset=UTF-8");
        } else if (responseText instanceof byte[]) {
            response = new DefaultHttpResponse(HTTP_1_1, OK);
            response.setContent(ChannelBuffers.wrappedBuffer((byte[]) responseText));
            response.setHeader(Names.CONTENT_TYPE, "application/json; charset=UTF-8");
        } else {
            logger.error("unexpected type of json service response '{}'", responseText.getClass()
                    .getName());
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        }
        // prevent caching of dynamic json data, using 'definitive' minimum set of headers from
        // http://stackoverflow.com/questions/49547/
        // making-sure-a-web-page-is-not-cached-across-all-browsers/2068407#2068407
        response.setHeader(Names.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
        response.setHeader(Names.PRAGMA, "no-cache");
        response.setHeader(Names.EXPIRES, new Date(0));
        return response;
    }

    @Nullable
    private static Object callMethod(Object object, String methodName, Object[] args,
            String optionalArg) throws NoSuchMethodException, IllegalAccessException,
            InvocationTargetException {

        boolean withOptionalArg = true;
        Method method;
        try {
            method = object.getClass().getDeclaredMethod(methodName,
                    getParameterTypes(args.length + 1));
        } catch (NoSuchMethodException e) {
            method = object.getClass()
                    .getDeclaredMethod(methodName, getParameterTypes(args.length));
            withOptionalArg = false;
        }
        if (withOptionalArg) {
            Object[] argsWithOptional = new String[args.length + 1];
            System.arraycopy(args, 0, argsWithOptional, 0, args.length);
            argsWithOptional[args.length] = optionalArg;
            return method.invoke(object, argsWithOptional);
        } else {
            return method.invoke(object, args);
        }
    }

    private static Class<?>[] getParameterTypes(int length) {
        Class<?>[] parameterTypes = new Class<?>[length];
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterTypes[i] = String.class;
        }
        return parameterTypes;
    }

    private String getRequestText(HttpRequest request, QueryStringDecoder decoder) {
        if (decoder.getParameters().isEmpty()) {
            return request.getContent().toString(Charsets.ISO_8859_1);
        } else {
            // create json message out of the query string
            // flatten map values from list to single element where possible
            Map<String, Object> parameters = Maps.newHashMap();
            for (Entry<String, List<String>> entry : decoder.getParameters().entrySet()) {
                String key = entry.getKey();
                key = convertUnderscoreToCamel(key);
                if (entry.getValue().size() == 1) {
                    parameters.put(key, entry.getValue().get(0));
                } else {
                    parameters.put(key, entry.getValue());
                }
            }
            return gson.toJson(parameters);
        }
    }

    private static String convertUnderscoreToCamel(String s) {
        int underscoreIndex;
        while ((underscoreIndex = s.indexOf('_')) != -1) {
            s = s.substring(0, underscoreIndex)
                    + Character.toUpperCase(s.charAt(underscoreIndex + 1))
                    + s.substring(underscoreIndex + 2);
        }
        return s;
    }

    @Immutable
    private static class JsonServiceMapping {
        private final Pattern pattern;
        private final JsonService service;
        private final String methodName;
        private JsonServiceMapping(Pattern pattern, JsonService service, String methodName) {
            this.pattern = pattern;
            this.service = service;
            this.methodName = methodName;
        }
    }
}
