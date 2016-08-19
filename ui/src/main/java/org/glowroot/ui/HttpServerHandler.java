/*
 * Copyright 2013-2016 the original author or authors.
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
import java.lang.annotation.Annotation;
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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import org.glowroot.common.util.ObjectMappers;
import org.glowroot.ui.HttpSessionManager.Authentication;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
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
    private static final ObjectMapper mapper = ObjectMappers.create();

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

    private final ThreadLocal</*@Nullable*/ Channel> currentChannel =
            new ThreadLocal</*@Nullable*/ Channel>();

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
                    jsonServiceMappings.add(build(HttpMethod.GET, annotationGET.path(),
                            annotationGET.permission(), jsonService, method));
                }
                POST annotationPOST = method.getAnnotation(POST.class);
                if (annotationPOST != null) {
                    jsonServiceMappings.add(build(HttpMethod.POST, annotationPOST.path(),
                            annotationPOST.permission(), jsonService, method));
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
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        FullHttpRequest request = (FullHttpRequest) msg;
        logger.debug("channelRead(): request.uri={}", request.uri());
        Channel channel = ctx.channel();
        currentChannel.set(channel);
        try {
            QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
            String path = decoder.path();
            logger.debug("handleRequest(): path={}", path);
            FullHttpResponse response = handleIfLoginOrLogoutRequest(path, request);
            if (response != null) {
                sendFullResponse(ctx, request, response, HttpUtil.isKeepAlive(request));
                return;
            }
            Authentication authentication = httpSessionManager.getAuthentication(request);
            response = handleRequest(path, ctx, request, authentication);
            if (response != null) {
                sendFullResponse(ctx, request, response, authentication);
            }
        } catch (Exception e) {
            logger.error("error handling request {}: {}", request.uri(), e.getMessage(), e);
            sendExceptionResponse(ctx, e);
        } finally {
            currentChannel.remove();
            request.release();
        }
    }

    private void sendFullResponse(ChannelHandlerContext ctx, FullHttpRequest request,
            FullHttpResponse response, Authentication authentication) throws Exception {
        if (httpSessionManager.getSessionId(request) != null && authentication.anonymous()) {
            httpSessionManager.deleteSessionCookie(response);
        }
        if (request.uri().startsWith("/backend/") && !request.uri().equals("/backend/layout")) {
            response.headers().add("Glowroot-Layout-Version",
                    layoutService.getLayoutVersion(authentication));
        }
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (response.headers().contains("Glowroot-Port-Changed")) {
            // current connection is the only open channel on the old port, keepAlive=false will add
            // the listener below to close the channel after the response completes
            //
            // remove the hacky header, no need to send it back to client
            response.headers().remove("Glowroot-Port-Changed");
            response.headers().add("Connection", "close");
            keepAlive = false;
        }
        sendFullResponse(ctx, request, response, keepAlive);
    }

    @SuppressWarnings("argument.type.incompatible")
    private void sendFullResponse(ChannelHandlerContext ctx, FullHttpRequest request,
            FullHttpResponse response, boolean keepAlive) {
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        if (keepAlive && !request.protocolVersion().isKeepAliveDefault()) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }
        ChannelFuture f = ctx.write(response);
        if (!keepAlive) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    // TODO report checker framework issue that occurs without this suppression
    @SuppressWarnings("argument.type.incompatible")
    private void sendExceptionResponse(ChannelHandlerContext ctx, Exception exception)
            throws Exception {
        FullHttpResponse response =
                newHttpResponseWithStackTrace(exception, INTERNAL_SERVER_ERROR, null);
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        ChannelFuture f = ctx.write(response);
        f.addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (HttpServices.shouldLogException(cause)) {
            logger.warn(cause.getMessage(), cause);
        }
        ctx.close();
    }

    private @Nullable FullHttpResponse handleRequest(String path, ChannelHandlerContext ctx,
            FullHttpRequest request, Authentication authentication) throws Exception {
        HttpService httpService = getHttpService(path);
        if (httpService != null) {
            return handleHttpService(ctx, request, httpService, authentication);
        }
        JsonServiceMapping jsonServiceMapping = getJsonServiceMapping(request, path);
        if (jsonServiceMapping != null) {
            return handleJsonServiceMappings(request, jsonServiceMapping, authentication);
        }
        return handleStaticResource(path, request);
    }

    private @Nullable FullHttpResponse handleIfLoginOrLogoutRequest(String path,
            FullHttpRequest request) throws Exception {
        if (path.equals("/backend/login")) {
            String content = request.content().toString(Charsets.ISO_8859_1);
            Credentials credentials = mapper.readValue(content, ImmutableCredentials.class);
            return httpSessionManager.login(credentials.username(), credentials.password());
        }
        if (path.equals("/backend/sign-out")) {
            httpSessionManager.signOut(request);
            Authentication authentication = httpSessionManager.getAnonymousAuthentication();
            String anonymousLayout = layoutService.getLayout(authentication);
            FullHttpResponse response = HttpServices.createJsonResponse(anonymousLayout, OK);
            httpSessionManager.deleteSessionCookie(response);
            return response;
        }
        return null;
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
            FullHttpRequest request, HttpService httpService, Authentication authentication)
            throws Exception {
        String permission = httpService.getPermission();
        if (permission.equals("")) {
            // service does not require any permission
            return httpService.handleRequest(ctx, request, authentication);
        }
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        List<String> values = decoder.parameters().get("agent-id");
        String agentId = values == null ? "" : values.get(0);
        if (!authentication.isPermitted(agentId, permission)) {
            if (authentication.anonymous()) {
                return handleNotAuthenticated(request);
            } else {
                return handleNotAuthorized();
            }
        }
        return httpService.handleRequest(ctx, request, authentication);
    }

    private @Nullable JsonServiceMapping getJsonServiceMapping(FullHttpRequest request,
            String path) {
        for (JsonServiceMapping jsonServiceMapping : jsonServiceMappings) {
            if (!jsonServiceMapping.httpMethod().name().equals(request.method().name())) {
                continue;
            }
            if (jsonServiceMapping.path().equals(path)) {
                return jsonServiceMapping;
            }
        }
        return null;
    }

    private FullHttpResponse handleJsonServiceMappings(FullHttpRequest request,
            JsonServiceMapping jsonServiceMapping, Authentication authentication) throws Exception {
        List<Class<?>> parameterTypes = Lists.newArrayList();
        List<Object> parameters = Lists.newArrayList();
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        Map<String, List<String>> queryParameters = decoder.parameters();
        boolean permitted;
        if (jsonServiceMapping.bindAgentId()) {
            List<String> values = queryParameters.get("agent-id");
            if (values == null) {
                throw new JsonServiceException(BAD_REQUEST, "missing agent-id query parameter");
            }
            String agentId = values.get(0);
            parameterTypes.add(String.class);
            parameters.add(agentId);
            queryParameters.remove("agent-id");
            permitted = authentication.isAgentPermitted(agentId, jsonServiceMapping.permission());
        } else if (jsonServiceMapping.bindAgentRollup()) {
            List<String> values = queryParameters.get("agent-rollup");
            if (values == null) {
                throw new JsonServiceException(BAD_REQUEST, "missing agent-rollup query parameter");
            }
            String agentRollup = values.get(0);
            parameterTypes.add(String.class);
            parameters.add(agentRollup);
            queryParameters.remove("agent-rollup");
            permitted =
                    authentication.isAgentPermitted(agentRollup, jsonServiceMapping.permission());
        } else {
            permitted = jsonServiceMapping.permission().isEmpty()
                    || authentication.isAdminPermitted(jsonServiceMapping.permission());
        }
        if (!permitted) {
            if (authentication.anonymous()) {
                return handleNotAuthenticated(request);
            } else {
                return handleNotAuthorized();
            }
        }
        Object responseObject;
        try {
            responseObject = callMethod(jsonServiceMapping, parameterTypes, parameters,
                    queryParameters, authentication.usernameCaseAmbiguous(), request);
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

    private static ImmutableJsonServiceMapping build(HttpMethod httpMethod, String path,
            String permission, Object jsonService, Method method) {
        boolean bindAgentId = false;
        boolean bindAgentRollup = false;
        Class<?> bindRequest = null;
        boolean bindCaseAmbiguousUsername = false;
        for (int i = 0; i < method.getParameterAnnotations().length; i++) {
            Annotation[] parameterAnnotations = method.getParameterAnnotations()[i];
            for (Annotation annotation : parameterAnnotations) {
                if (annotation.annotationType() == BindAgentId.class) {
                    bindAgentId = true;
                } else if (annotation.annotationType() == BindAgentRollup.class) {
                    bindAgentRollup = true;
                } else if (annotation.annotationType() == BindRequest.class) {
                    bindRequest = method.getParameterTypes()[i];
                } else if (annotation.annotationType() == BindCaseAmbiguousUsername.class) {
                    bindCaseAmbiguousUsername = true;
                }
            }
        }
        return ImmutableJsonServiceMapping.builder()
                .httpMethod(httpMethod)
                .path(path)
                .permission(permission)
                .service(jsonService)
                .method(method)
                .bindAgentId(bindAgentId)
                .bindAgentRollup(bindAgentRollup)
                .bindRequest(bindRequest)
                .bindCaseAmbiguousUsername(bindCaseAmbiguousUsername)
                .build();
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
            JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
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
            JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
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

    private static @Nullable Object callMethod(JsonServiceMapping jsonServiceMapping,
            List<Class<?>> parameterTypes, List<Object> parameters,
            Map<String, List<String>> queryParameters, String usernameCaseAmbiguous,
            FullHttpRequest request) throws Exception {
        Class<?> bindRequest = jsonServiceMapping.bindRequest();
        if (bindRequest != null) {
            parameterTypes.add(bindRequest);
            if (jsonServiceMapping.httpMethod() == HttpMethod.GET) {
                parameters.add(QueryStrings.decode(queryParameters, bindRequest));
            } else {
                String content = request.content().toString(Charsets.ISO_8859_1);
                if (bindRequest == String.class) {
                    parameters.add(content);
                } else {
                    // TODO report checker framework issue that occurs without this suppression
                    @SuppressWarnings("argument.type.incompatible")
                    Object param = checkNotNull(
                            mapper.readValue(content, QueryStrings.getImmutableClass(bindRequest)));
                    parameters.add(param);
                }
            }
        }
        if (jsonServiceMapping.bindCaseAmbiguousUsername()) {
            parameterTypes.add(String.class);
            parameters.add(usernameCaseAmbiguous);
        }
        Object service = jsonServiceMapping.service();
        if (logger.isDebugEnabled()) {
            String params = Joiner.on(", ").join(parameters);
            logger.debug("{}.{}(): {}", service.getClass().getSimpleName(),
                    jsonServiceMapping.method().getName(), params);
        }
        return jsonServiceMapping.method().invoke(service,
                parameters.toArray(new Object[parameters.size()]));
    }

    @Value.Immutable
    interface Credentials {
        String username();
        String password();
    }

    @Value.Immutable
    interface JsonServiceMapping {
        HttpMethod httpMethod();
        String path();
        String permission();
        Object service();
        Method method();
        boolean bindAgentId();
        boolean bindAgentRollup();
        @Nullable
        Class<?> bindRequest();
        boolean bindCaseAmbiguousUsername();
    }

    enum HttpMethod {
        GET, POST
    }
}
