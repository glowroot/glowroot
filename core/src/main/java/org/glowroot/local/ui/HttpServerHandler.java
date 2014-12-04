/*
 * Copyright 2013-2014 the original author or authors.
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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.channels.ClosedChannelException;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.google.common.io.Resources;
import com.google.common.net.MediaType;
import org.h2.api.ErrorCode;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpHeaders.Names;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Reflections;
import org.glowroot.common.Reflections.ReflectiveException;
import org.glowroot.common.Reflections.ReflectiveTargetException;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.glowroot.local.ui.HttpServerHandler.HttpMethod.GET;
import static org.glowroot.local.ui.HttpServerHandler.HttpMethod.POST;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.NOT_MODIFIED;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.REQUEST_TIMEOUT;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

class HttpServerHandler extends SimpleChannelUpstreamHandler {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerHandler.class);
    private static final JsonFactory jsonFactory = new JsonFactory();

    private static final long TEN_YEARS = DAYS.toMillis(365 * 10);
    private static final long ONE_DAY = DAYS.toMillis(1);
    private static final long FIVE_MINUTES = MINUTES.toMillis(5);

    private static final ImmutableSet<String> BROWSER_DISCONNECT_MESSAGES = ImmutableSet.of(
            "An existing connection was forcibly closed by the remote host",
            "An established connection was aborted by the software in your host machine",
            "Connection reset by peer");

    private static final ImmutableMap<String, MediaType> mediaTypes =
            ImmutableMap.<String, MediaType>builder()
                    .put("html", MediaType.HTML_UTF_8)
                    .put("js", MediaType.JAVASCRIPT_UTF_8)
                    .put("css", MediaType.CSS_UTF_8)
                    .put("png", MediaType.PNG)
                    .put("ico", MediaType.ICO)
                    .put("woff", MediaType.WOFF)
                    .put("eot", MediaType.EOT)
                    .put("ttf", MediaType.SFNT)
                    .put("swf", MediaType.SHOCKWAVE_FLASH)
                    .put("map", MediaType.JSON_UTF_8)
                    .build();

    private final ChannelGroup allChannels;

    private final LayoutJsonService layoutJsonService;
    private final ImmutableMap<Pattern, Object> uriMappings;
    private final ImmutableList<JsonServiceMapping> jsonServiceMappings;
    private final HttpSessionManager httpSessionManager;

    private final ThreadLocal</*@Nullable*/Channel> currentChannel =
            new ThreadLocal</*@Nullable*/Channel>();

    HttpServerHandler(LayoutJsonService layoutJsonService,
            ImmutableMap<Pattern, Object> uriMappings, HttpSessionManager httpSessionManager,
            List<Object> jsonServices) {
        this.layoutJsonService = layoutJsonService;
        this.uriMappings = uriMappings;
        this.httpSessionManager = httpSessionManager;
        List<JsonServiceMapping> jsonServiceMappings = Lists.newArrayList();
        for (Object jsonService : jsonServices) {
            for (Method method : jsonService.getClass().getDeclaredMethods()) {
                GET annotationGET = method.getAnnotation(GET.class);
                if (annotationGET != null) {
                    jsonServiceMappings.add(new JsonServiceMapping(GET, annotationGET.value(),
                            jsonService, method.getName()));
                }
                POST annotationPOST = method.getAnnotation(POST.class);
                if (annotationPOST != null) {
                    jsonServiceMappings.add(new JsonServiceMapping(POST, annotationPOST.value(),
                            jsonService, method.getName()));
                }
            }
        }

        this.jsonServiceMappings = ImmutableList.copyOf(jsonServiceMappings);
        allChannels = new DefaultChannelGroup();
    }

    @Override
    public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) {
        logger.debug("channelOpen()");
        allChannels.add(e.getChannel());
    }

    @Override
    // @SuppressWarnings needed until this Checker Framework bug is fixed:
    // https://code.google.com/p/checker-framework/issues/detail?id=293
    @SuppressWarnings("initialization")
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        HttpRequest request = (HttpRequest) e.getMessage();
        logger.debug("messageReceived(): request.uri={}", request.getUri());
        Channel channel = e.getChannel();
        currentChannel.set(channel);
        HttpResponse response;
        try {
            response = handleRequest(request, channel);
        } finally {
            currentChannel.remove();
        }
        if (response == null) {
            // streaming response
            return;
        }
        boolean keepAlive = HttpHeaders.isKeepAlive(request);
        if (response.headers().get("Glowroot-Port-Changed") != null) {
            // current connection is the only open channel on the old port, keepAlive=false will add
            // the listener below to close the channel after the response completes
            //
            // remove the hacky header, no need to send it back to client
            response.headers().remove("Glowroot-Port-Changed");
            response.headers().add("Connection", "close");
            keepAlive = false;
        }
        if (keepAlive && response.getStatus() != NOT_MODIFIED) {
            // add content-length header only for keep-alive connections
            response.headers().add(Names.CONTENT_LENGTH, response.getContent().readableBytes());
        }
        logger.debug("messageReceived(): response={}", response);
        ChannelFuture f = channel.write(response);
        if (!keepAlive) {
            // close non- keep-alive connections after the write operation is done
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
        if (e.getCause() instanceof InterruptedException) {
            // ignore, probably just termination
        } else {
            if (e.getCause() instanceof IOException
                    && BROWSER_DISCONNECT_MESSAGES.contains(e.getCause().getMessage())) {
                // ignore, just a browser disconnect
            } else if (e.getCause() instanceof ClosedChannelException) {
                // ignore, just a browser disconnect
            } else {
                logger.warn(e.getCause().getMessage(), e.getCause());
            }
        }
        e.getChannel().close();
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) {
        logger.debug("channelClosed()");
    }

    void close() {
        allChannels.close().awaitUninterruptibly();
    }

    void closeAllButCurrent() {
        Channel current = currentChannel.get();
        for (Channel channel : allChannels) {
            if (channel != current) {
                channel.close().awaitUninterruptibly();
            }
        }
    }

    private @Nullable HttpResponse handleRequest(HttpRequest request, Channel channel)
            throws Exception {
        logger.debug("handleRequest(): request.uri={}", request.getUri());
        QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());
        String path = decoder.getPath();
        logger.debug("handleRequest(): path={}", path);
        if (path.equals("/backend/login")) {
            return httpSessionManager.login(request);
        }
        if (path.equals("/backend/sign-out")) {
            return httpSessionManager.signOut(request);
        }
        for (Entry<Pattern, Object> uriMappingEntry : uriMappings.entrySet()) {
            Matcher matcher = uriMappingEntry.getKey().matcher(path);
            if (matcher.matches()) {
                if (uriMappingEntry.getValue() instanceof HttpService) {
                    if (httpSessionManager.needsAuthentication(request)) {
                        return handleUnauthorized(request);
                    }
                    return ((HttpService) uriMappingEntry.getValue()).handleRequest(request,
                            channel);
                } else {
                    // only other value type is String
                    String resourcePath = matcher.replaceFirst((String) uriMappingEntry.getValue());
                    return handleStaticResource(resourcePath, request);
                }
            }
        }
        for (JsonServiceMapping jsonServiceMapping : jsonServiceMappings) {
            if (!jsonServiceMapping.httpMethod.name().equals(request.getMethod().getName())) {
                continue;
            }
            Matcher matcher = jsonServiceMapping.pattern.matcher(path);
            if (matcher.matches()) {
                if (httpSessionManager.needsAuthentication(request)) {
                    return handleUnauthorized(request);
                }
                String requestText = getRequestText(request);
                String[] args = new String[matcher.groupCount()];
                for (int i = 0; i < args.length; i++) {
                    String group = matcher.group(i + 1);
                    checkNotNull(group);
                    args[i] = group;
                }
                return handleJsonRequest(jsonServiceMapping.service, jsonServiceMapping.methodName,
                        args, requestText);
            }
        }
        logger.warn("unexpected uri: {}", request.getUri());
        return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
    }

    private HttpResponse handleUnauthorized(HttpRequest request) {
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, UNAUTHORIZED);
        if (httpSessionManager.getSessionId(request) != null) {
            response.setContent(ChannelBuffers.copiedBuffer("{\"timedOut\":true}", Charsets.UTF_8));
        }
        return response;
    }

    private HttpResponse handleStaticResource(String path, HttpRequest request) throws IOException {
        int extensionStartIndex = path.lastIndexOf('.');
        if (extensionStartIndex == -1) {
            logger.warn("path has no extension: {}", path);
            return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        String extension = path.substring(extensionStartIndex + 1);
        MediaType mediaType = mediaTypes.get(extension);
        if (mediaType == null) {
            logger.warn("path {} has unexpected extension: {}", path, extension);
            return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        if (path.startsWith("org/glowroot/local/ui/app-dist/favicon.")) {
            response.headers().add(Names.EXPIRES, new Date(System.currentTimeMillis() + ONE_DAY));
        } else if (path.endsWith(".js.map") || path.startsWith("/sources/")) {
            // javascript source maps and source files are not versioned
            response.headers().add(Names.EXPIRES,
                    new Date(System.currentTimeMillis() + FIVE_MINUTES));
        } else {
            // all other static resources are versioned and can be safely cached forever
            String filename = path.substring(path.lastIndexOf('/') + 1);
            int to = filename.lastIndexOf('.');
            int from = filename.lastIndexOf('.', to - 1);
            String rev = filename.substring(from + 1, to);
            response.headers().add(Names.ETAG, rev);
            response.headers().add(Names.EXPIRES, new Date(System.currentTimeMillis() + TEN_YEARS));

            if (rev.equals(request.headers().get(Names.IF_NONE_MATCH))) {
                response.setStatus(NOT_MODIFIED);
                return response;
            }
        }
        URL url;
        ClassLoader classLoader = HttpServerHandler.class.getClassLoader();
        if (classLoader == null) {
            url = ClassLoader.getSystemResource(path);
        } else {
            url = classLoader.getResource(path);
        }
        if (url == null) {
            logger.warn("unexpected path: {}", path);
            return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        byte[] staticContent = Resources.toByteArray(url);
        response.setContent(ChannelBuffers.copiedBuffer(staticContent));
        response.headers().add(Names.CONTENT_TYPE, mediaType);
        response.headers().add(Names.CONTENT_LENGTH, staticContent.length);
        return response;
    }

    private HttpResponse handleJsonRequest(Object jsonService, String serviceMethodName,
            String[] args, String requestText) {

        logger.debug("handleJsonRequest(): serviceMethodName={}, args={}, requestText={}",
                serviceMethodName, args, requestText);

        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        Object responseText;
        try {
            responseText = callMethod(jsonService, serviceMethodName, args, requestText, response);
        } catch (ReflectiveTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof JsonServiceException) {
                // this is an "expected" exception, no need to log
                JsonServiceException jsonServiceException = (JsonServiceException) cause;
                return newHttpResponseFromJsonServiceException(jsonServiceException.getMessage(),
                        jsonServiceException.getStatus());
            }
            logger.error(e.getMessage(), e);
            if (cause instanceof SQLException
                    && ((SQLException) cause).getErrorCode() == ErrorCode.STATEMENT_WAS_CANCELED) {
                return newHttpResponseWithStackTrace(e, REQUEST_TIMEOUT,
                        "Query timed out (timeout is configurable under Configuration > Advanced)");
            }
            return newHttpResponseWithStackTrace(e, INTERNAL_SERVER_ERROR, null);
        } catch (ReflectiveException e) {
            logger.error(e.getMessage(), e);
            return newHttpResponseWithStackTrace(e, INTERNAL_SERVER_ERROR, null);
        }
        if (responseText == null) {
            response.setContent(ChannelBuffers.EMPTY_BUFFER);
            response.headers().add(Names.CONTENT_TYPE, MediaType.JSON_UTF_8);
            response.headers().add("Glowroot-Layout-Version", layoutJsonService.getLayoutVersion());
            HttpServices.preventCaching(response);
            return response;
        }
        if (responseText instanceof String) {
            response.setContent(ChannelBuffers.copiedBuffer(responseText.toString(),
                    Charsets.ISO_8859_1));
            response.headers().add(Names.CONTENT_TYPE, MediaType.JSON_UTF_8);
            response.headers().add("Glowroot-Layout-Version", layoutJsonService.getLayoutVersion());
            HttpServices.preventCaching(response);
            return response;
        }
        if (responseText instanceof byte[]) {
            response.setContent(ChannelBuffers.wrappedBuffer((byte[]) responseText));
            response.headers().add(Names.CONTENT_TYPE, MediaType.JSON_UTF_8);
            response.headers().add("Glowroot-Layout-Version", layoutJsonService.getLayoutVersion());
            HttpServices.preventCaching(response);
            return response;
        }
        logger.warn("unexpected type of json service response: {}",
                responseText.getClass().getName());
        return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
    }

    private static HttpResponse newHttpResponseFromJsonServiceException(@Nullable String message,
            HttpResponseStatus status) {
        // this is an "expected" exception, no need to send back stack trace
        StringBuilder sb = new StringBuilder();
        try {
            JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
            jg.writeStartObject();
            jg.writeStringField("message", message);
            jg.writeEndObject();
            jg.close();
            DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, status);
            response.headers().add(Names.CONTENT_TYPE, MediaType.JSON_UTF_8);
            response.setContent(ChannelBuffers.copiedBuffer(sb.toString(), Charsets.ISO_8859_1));
            return response;
        } catch (IOException f) {
            logger.error(f.getMessage(), f);
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        }
    }

    private static HttpResponse newHttpResponseWithStackTrace(Exception e,
            HttpResponseStatus status, @Nullable String simplifiedMessage) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        StringBuilder sb = new StringBuilder();
        try {
            JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
            jg.writeStartObject();
            String message;
            if (simplifiedMessage == null) {
                Throwable cause = e;
                Throwable childCause = cause.getCause();
                while (childCause != null) {
                    cause = childCause;
                    childCause = cause.getCause();
                }
                message = cause.getMessage();
            } else {
                message = simplifiedMessage;
            }
            jg.writeStringField("message", message);
            jg.writeStringField("stackTrace", sw.toString());
            jg.writeEndObject();
            jg.close();
            DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, status);
            response.headers().add(Names.CONTENT_TYPE, MediaType.JSON_UTF_8);
            response.setContent(ChannelBuffers.copiedBuffer(sb.toString(), Charsets.ISO_8859_1));
            return response;
        } catch (IOException f) {
            logger.error(f.getMessage(), f);
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        }
    }

    private static @Nullable Object callMethod(Object object, String methodName, String[] args,
            String requestText, HttpResponse response) throws ReflectiveException {
        List<Class<?>> parameterTypes = Lists.newArrayList();
        List<Object> parameters = Lists.newArrayList();
        for (int i = 0; i < args.length; i++) {
            parameterTypes.add(String.class);
            parameters.add(args[i]);
        }
        Method method = null;
        try {
            method = Reflections.getDeclaredMethod(object.getClass(), methodName,
                    parameterTypes.toArray(new Class[parameterTypes.size()]));
        } catch (ReflectiveException e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            // try again with requestText
            parameterTypes.add(String.class);
            parameters.add(requestText);
            try {
                method = Reflections.getDeclaredMethod(object.getClass(), methodName,
                        parameterTypes.toArray(new Class[parameterTypes.size()]));
            } catch (ReflectiveException f) {
                // log exception at trace level
                logger.trace(f.getMessage(), f);
                // try again with response
                parameterTypes.add(HttpResponse.class);
                parameters.add(response);
                try {
                    method = Reflections.getDeclaredMethod(object.getClass(), methodName,
                            parameterTypes.toArray(new Class[parameterTypes.size()]));
                } catch (ReflectiveException g) {
                    // log exception at trace level
                    logger.trace(g.getMessage(), g);
                    throw new ReflectiveException(new NoSuchMethodException(methodName));
                }
            }
        }
        if (logger.isDebugEnabled()) {
            String params = Joiner.on(", ").join(parameters);
            logger.debug("{}.{}(): {}", object.getClass().getSimpleName(), methodName, params);
        }
        return Reflections.invoke(method, object,
                parameters.toArray(new Object[parameters.size()]));
    }

    private static String getRequestText(HttpRequest request) throws JsonProcessingException {
        if (request.getMethod() == org.jboss.netty.handler.codec.http.HttpMethod.POST) {
            return request.getContent().toString(Charsets.ISO_8859_1);
        } else {
            int index = request.getUri().indexOf('?');
            if (index == -1) {
                return "";
            } else {
                return request.getUri().substring(index + 1);
            }
        }
    }

    private static class JsonServiceMapping {

        private final HttpMethod httpMethod;
        private final Pattern pattern;
        private final Object service;
        private final String methodName;

        private JsonServiceMapping(HttpMethod httpMethod, String path, Object service,
                String methodName) {
            this.httpMethod = httpMethod;
            this.service = service;
            this.methodName = methodName;
            if (path.contains("(")) {
                pattern = Pattern.compile(path);
            } else {
                pattern = Pattern.compile(Pattern.quote(path));
            }

        }
    }

    static enum HttpMethod {
        GET, POST
    }
}
