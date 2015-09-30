/*
 * Copyright 2013-2015 the original author or authors.
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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.google.common.io.Resources;
import com.google.common.net.MediaType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.h2.api.ErrorCode;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_MODIFIED;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.REQUEST_TIMEOUT;
import static io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MINUTES;

@Sharable
class HttpServerHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerHandler.class);
    private static final JsonFactory jsonFactory = new JsonFactory();

    private static final long TEN_YEARS = DAYS.toMillis(365 * 10);
    private static final long ONE_DAY = DAYS.toMillis(1);
    private static final long FIVE_MINUTES = MINUTES.toMillis(5);

    private static final String RESOURCE_BASE = "org/glowroot/ui/app-dist";
    // only null when running tests with glowroot.ui.skip=true (e.g. travis "deploy" build)
    private static final @Nullable String RESOURCE_BASE_URL_PREFIX;

    private static final ImmutableMap<String, MediaType> mediaTypes =
            ImmutableMap.<String, MediaType>builder()
                    .put("html", MediaType.HTML_UTF_8)
                    .put("js", MediaType.JAVASCRIPT_UTF_8)
                    .put("css", MediaType.CSS_UTF_8)
                    .put("ico", MediaType.ICO)
                    .put("woff", MediaType.WOFF)
                    .put("woff2", MediaType.create("application", "font-woff2"))
                    .put("swf", MediaType.create("application", "vnd.adobe.flash-movie"))
                    .put("map", MediaType.JSON_UTF_8)
                    .build();

    static {
        URL resourceBaseUrl = getUrlForPath(RESOURCE_BASE);
        if (resourceBaseUrl == null) {
            RESOURCE_BASE_URL_PREFIX = null;
        } else {
            RESOURCE_BASE_URL_PREFIX = resourceBaseUrl.toExternalForm();
        }
    }

    private final ChannelGroup allChannels;

    private final LayoutService layoutService;
    private final ImmutableMap<Pattern, HttpService> httpServices;
    private final ImmutableList<JsonServiceMapping> jsonServiceMappings;
    private final HttpSessionManager httpSessionManager;

    private final ThreadLocal</*@Nullable*/Channel> currentChannel =
            new ThreadLocal</*@Nullable*/Channel>();

    HttpServerHandler(LayoutService layoutService, Map<Pattern, HttpService> httpServices,
            HttpSessionManager httpSessionManager, List<Object> jsonServices) {
        this.layoutService = layoutService;
        this.httpServices = ImmutableMap.copyOf(httpServices);
        this.httpSessionManager = httpSessionManager;
        List<JsonServiceMapping> jsonServiceMappings = Lists.newArrayList();
        for (Object jsonService : jsonServices) {
            for (Method method : jsonService.getClass().getDeclaredMethods()) {
                GET annotationGET = method.getAnnotation(GET.class);
                if (annotationGET != null) {
                    jsonServiceMappings.add(ImmutableJsonServiceMapping.builder()
                            .httpMethod(HttpMethod.GET)
                            .path(annotationGET.value())
                            .service(jsonService)
                            .methodName(method.getName())
                            .build());
                }
                POST annotationPOST = method.getAnnotation(POST.class);
                if (annotationPOST != null) {
                    jsonServiceMappings.add(ImmutableJsonServiceMapping.builder()
                            .httpMethod(HttpMethod.POST)
                            .path(annotationPOST.value())
                            .service(jsonService)
                            .methodName(method.getName())
                            .build());
                }
            }
        }
        this.jsonServiceMappings = ImmutableList.copyOf(jsonServiceMappings);
        allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    }
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        allChannels.add(ctx.channel());
        super.channelActive(ctx);
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

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        FullHttpRequest request = (FullHttpRequest) msg;
        logger.debug("messageReceived(): request.uri={}", request.uri());
        Channel channel = ctx.channel();
        currentChannel.set(channel);
        try {
            FullHttpResponse response = handleRequest(ctx, request);
            if (response != null) {
                sendFullResponse(ctx, request, response);
            }
        } catch (Exception f) {
            logger.error(f.getMessage(), f);
            FullHttpResponse response =
                    newHttpResponseWithStackTrace(f, INTERNAL_SERVER_ERROR, null);
            sendFullResponse(ctx, request, response);
        } finally {
            currentChannel.remove();
            request.release();
        }
    }

    @SuppressWarnings("argument.type.incompatible")
    private void sendFullResponse(ChannelHandlerContext ctx, FullHttpRequest request,
            FullHttpResponse response) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (httpSessionManager.getSessionId(request) != null
                && httpSessionManager.getAuthenticatedUser(request) == null
                && !response.headers().contains("Set-Cookie")) {
            httpSessionManager.deleteSessionCookie(response);
        }
        response.headers().add("Glowroot-Layout-Version", layoutService.getLayoutVersion());
        if (response.headers().contains("Glowroot-Port-Changed")) {
            // current connection is the only open channel on the old port, keepAlive=false will add
            // the listener below to close the channel after the response completes
            //
            // remove the hacky header, no need to send it back to client
            response.headers().remove("Glowroot-Port-Changed");
            response.headers().add("Connection", "close");
            keepAlive = false;
        }
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        if (keepAlive && !request.protocolVersion().isKeepAliveDefault()) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }
        ChannelFuture f = ctx.write(response);
        if (!keepAlive) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (HttpServices.shouldLogException(cause)) {
            logger.warn(cause.getMessage(), cause);
        }
        ctx.close();
    }

    private @Nullable FullHttpResponse handleRequest(ChannelHandlerContext ctx,
            FullHttpRequest request) throws Exception {
        logger.debug("handleRequest(): request.uri={}", request.uri());
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        String path = decoder.path();
        logger.debug("handleRequest(): path={}", path);
        FullHttpResponse response = handleIfLoginOrLogoutRequest(path, request);
        if (response != null) {
            return response;
        }
        HttpService httpService = getHttpService(path);
        if (httpService != null) {
            return handleHttpService(ctx, request, httpService);
        }
        JsonServiceMatcher jsonServiceMatcher = getJsonServiceMatcher(request, path);
        if (jsonServiceMatcher != null) {
            return handleJsonServiceMappings(request, jsonServiceMatcher.jsonServiceMapping(),
                    jsonServiceMatcher.matcher());
        }
        return handleStaticResource(path, request);
    }

    private @Nullable FullHttpResponse handleIfLoginOrLogoutRequest(String path,
            FullHttpRequest request) throws IOException {
        if (path.equals("/backend/authenticated-user")) {
            // this is only used when running under 'grunt serve'
            return handleAuthenticatedUserRequest(request);
        }
        if (path.equals("/backend/admin-login")) {
            return httpSessionManager.login(request, true);
        }
        if (path.equals("/backend/read-only-login")) {
            return httpSessionManager.login(request, false);
        }
        if (path.equals("/backend/sign-out")) {
            return httpSessionManager.signOut(request);
        }
        return null;
    }

    private FullHttpResponse handleAuthenticatedUserRequest(FullHttpRequest request) {
        String authenticatedUser = httpSessionManager.getAuthenticatedUser(request);
        if (authenticatedUser == null) {
            return HttpServices.createJsonResponse("null", OK);
        } else {
            return HttpServices.createJsonResponse("\"" + authenticatedUser + "\"", OK);
        }
    }

    private @Nullable HttpService getHttpService(String path) throws Exception {
        for (Entry<Pattern, HttpService> entry : httpServices.entrySet()) {
            Matcher matcher = entry.getKey().matcher(path);
            if (matcher.matches()) {
                return entry.getValue();
            }
        }
        return null;
    }

    private @Nullable FullHttpResponse handleHttpService(ChannelHandlerContext ctx,
            FullHttpRequest request, HttpService httpService) throws Exception {
        if (!httpSessionManager.hasReadAccess(request)
                && !(httpService instanceof UnauthenticatedHttpService)) {
            return handleNotAuthenticated(request);
        }
        boolean isGetRequest = request.method().name().equals(HttpMethod.GET.name());
        if (!isGetRequest && !httpSessionManager.hasAdminAccess(request)) {
            return handleNotAuthorized();
        }
        return httpService.handleRequest(ctx, request);
    }

    private @Nullable JsonServiceMatcher getJsonServiceMatcher(FullHttpRequest request,
            String path) {
        for (JsonServiceMapping jsonServiceMapping : jsonServiceMappings) {
            if (!jsonServiceMapping.httpMethod().name().equals(request.method().name())) {
                continue;
            }
            Matcher matcher = jsonServiceMapping.pattern().matcher(path);
            if (matcher.matches()) {
                return ImmutableJsonServiceMatcher.of(jsonServiceMapping, matcher);
            }
        }
        return null;
    }

    private FullHttpResponse handleJsonServiceMappings(FullHttpRequest request,
            JsonServiceMapping jsonServiceMapping, Matcher matcher) {
        if (!httpSessionManager.hasReadAccess(request)) {
            return handleNotAuthenticated(request);
        }
        boolean isGetRequest = request.method().name().equals(HttpMethod.GET.name());
        if (!isGetRequest && !httpSessionManager.hasAdminAccess(request)) {
            return handleNotAuthorized();
        }
        String requestText = getRequestText(request);
        String[] args = new String[matcher.groupCount()];
        for (int i = 0; i < args.length; i++) {
            String group = matcher.group(i + 1);
            checkNotNull(group);
            args[i] = group;
        }
        logger.debug("handleJsonRequest(): serviceMethodName={}, args={}, requestText={}",
                jsonServiceMapping.methodName(), args, requestText);
        Object responseObject;
        try {
            responseObject = callMethod(jsonServiceMapping.service(),
                    jsonServiceMapping.methodName(), args, requestText);
        } catch (Exception e) {
            return newHttpResponseFromException(e);
        }
        return buildJsonResponse(responseObject);
    }

    private FullHttpResponse buildJsonResponse(@Nullable Object responseObject) {
        FullHttpResponse response;
        if (responseObject == null) {
            response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        } else if (responseObject instanceof FullHttpResponse) {
            response = (FullHttpResponse) responseObject;
        } else if (responseObject instanceof String) {
            ByteBuf content = Unpooled.copiedBuffer(responseObject.toString(), Charsets.ISO_8859_1);
            response = new DefaultFullHttpResponse(HTTP_1_1, OK, content);
        } else {
            logger.warn("unexpected type of json service response: {}",
                    responseObject.getClass().getName());
            return new DefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        }
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8);
        HttpServices.preventCaching(response);
        return response;
    }

    private FullHttpResponse handleNotAuthenticated(HttpRequest request) {
        if (httpSessionManager.getSessionId(request) != null) {
            return HttpServices.createJsonResponse("{\"timedOut\":true}", UNAUTHORIZED);
        } else {
            return new DefaultFullHttpResponse(HTTP_1_1, UNAUTHORIZED);
        }
    }

    private FullHttpResponse handleNotAuthorized() {
        return new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN);
    }

    private FullHttpResponse handleStaticResource(String path, HttpRequest request)
            throws IOException {
        URL url = getSecureUrlForPath(RESOURCE_BASE + path);
        if (url == null) {
            // log at debug only since this is typically just exploit bot spam
            logger.debug("unexpected path: {}", path);
            return new DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        Date expires = getExpiresForPath(path);
        if (request.headers().contains(HttpHeaderNames.IF_MODIFIED_SINCE) && expires == null) {
            // all static resources without explicit expires are versioned and can be safely
            // cached forever
            return new DefaultFullHttpResponse(HTTP_1_1, NOT_MODIFIED);
        }
        ByteBuf content = Unpooled.copiedBuffer(Resources.toByteArray(url));
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, content);
        if (expires != null) {
            response.headers().add(HttpHeaderNames.EXPIRES, expires);
        } else {
            response.headers().add(HttpHeaderNames.LAST_MODIFIED, new Date(0));
            response.headers().add(HttpHeaderNames.EXPIRES,
                    new Date(System.currentTimeMillis() + TEN_YEARS));
        }
        int extensionStartIndex = path.lastIndexOf('.');
        checkState(extensionStartIndex != -1, "found path under %s with no extension: %s",
                RESOURCE_BASE, path);
        String extension = path.substring(extensionStartIndex + 1);
        MediaType mediaType = mediaTypes.get(extension);
        checkNotNull(mediaType, "found extension under %s with no media type: %s", RESOURCE_BASE,
                extension);
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, mediaType);
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, Resources.toByteArray(url).length);
        return response;
    }

    private static @Nullable URL getSecureUrlForPath(String path) {
        URL url = getUrlForPath(path);
        if (url != null && RESOURCE_BASE_URL_PREFIX != null
                && url.toExternalForm().startsWith(RESOURCE_BASE_URL_PREFIX)) {
            return url;
        }
        return null;
    }

    private static @Nullable URL getUrlForPath(String path) {
        ClassLoader classLoader = HttpServerHandler.class.getClassLoader();
        if (classLoader == null) {
            return ClassLoader.getSystemResource(path);
        } else {
            return classLoader.getResource(path);
        }
    }

    private static @Nullable Date getExpiresForPath(String path) {
        if (path.startsWith("org/glowroot/ui/app-dist/favicon.")) {
            return new Date(System.currentTimeMillis() + ONE_DAY);
        } else if (path.endsWith(".js.map") || path.startsWith("/sources/")) {
            // javascript source maps and source files are not versioned
            return new Date(System.currentTimeMillis() + FIVE_MINUTES);
        } else {
            return null;
        }
    }

    @VisibleForTesting
    static FullHttpResponse newHttpResponseFromException(Exception exception) {
        Exception e = exception;
        if (e instanceof InvocationTargetException) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                e = (Exception) cause;
            }
        }
        if (e instanceof JsonServiceException) {
            // this is an "expected" exception, no need to log
            JsonServiceException jsonServiceException = (JsonServiceException) e;
            return newHttpResponseWithMessage(jsonServiceException.getStatus(),
                    jsonServiceException.getMessage());
        }
        logger.error(e.getMessage(), e);
        if (e instanceof SQLException
                && ((SQLException) e).getErrorCode() == ErrorCode.STATEMENT_WAS_CANCELED) {
            return newHttpResponseWithMessage(REQUEST_TIMEOUT,
                    "Query timed out (timeout is configurable under Configuration > Advanced)");
        }
        return newHttpResponseWithStackTrace(e, INTERNAL_SERVER_ERROR, null);
    }

    private static FullHttpResponse newHttpResponseWithMessage(HttpResponseStatus status,
            @Nullable String message) {
        // this is an "expected" exception, no need to send back stack trace
        StringBuilder sb = new StringBuilder();
        try {
            JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
            jg.writeStartObject();
            jg.writeStringField("message", message);
            jg.writeEndObject();
            jg.close();
            return HttpServices.createJsonResponse(sb.toString(), status);
        } catch (IOException f) {
            logger.error(f.getMessage(), f);
            return new DefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        }
    }

    private static FullHttpResponse newHttpResponseWithStackTrace(Exception e,
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
            return HttpServices.createJsonResponse(sb.toString(), status);
        } catch (IOException f) {
            logger.error(f.getMessage(), f);
            return new DefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        }
    }

    private static @Nullable Object callMethod(Object object, String methodName, String[] args,
            String requestText) throws Exception {
        List<Class<?>> parameterTypes = Lists.newArrayList();
        List<Object> parameters = Lists.newArrayList();
        for (int i = 0; i < args.length; i++) {
            parameterTypes.add(String.class);
            parameters.add(args[i]);
        }
        Method method = null;
        try {
            method = object.getClass().getDeclaredMethod(methodName,
                    parameterTypes.toArray(new Class[parameterTypes.size()]));
        } catch (Exception e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            // try again with requestText
            parameterTypes.add(String.class);
            parameters.add(requestText);
            try {
                method = object.getClass().getDeclaredMethod(methodName,
                        parameterTypes.toArray(new Class[parameterTypes.size()]));
            } catch (Exception f) {
                // log exception at debug level
                logger.trace(f.getMessage(), f);
                throw new NoSuchMethodException(methodName);
            }
        }
        if (logger.isDebugEnabled()) {
            String params = Joiner.on(", ").join(parameters);
            logger.debug("{}.{}(): {}", object.getClass().getSimpleName(), methodName, params);
        }
        return method.invoke(object, parameters.toArray(new Object[parameters.size()]));
    }

    private static String getRequestText(FullHttpRequest request) {
        if (request.method() == io.netty.handler.codec.http.HttpMethod.POST) {
            return request.content().toString(Charsets.ISO_8859_1);
        } else {
            int index = request.uri().indexOf('?');
            if (index == -1) {
                return "";
            } else {
                return request.uri().substring(index + 1);
            }
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    interface JsonServiceMatcher {
        JsonServiceMapping jsonServiceMapping();
        Matcher matcher();
    }

    @Value.Immutable
    abstract static class JsonServiceMapping {

        abstract HttpMethod httpMethod();
        abstract String path();
        abstract Object service();
        abstract String methodName();

        @Value.Derived
        Pattern pattern() {
            String path = path();
            if (path.contains("(")) {
                return Pattern.compile(path);
            } else {
                return Pattern.compile(Pattern.quote(path));
            }
        }
    }

    enum HttpMethod {
        GET, POST
    }
}
