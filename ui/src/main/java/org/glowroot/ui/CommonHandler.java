/*
 * Copyright 2017-2018 the original author or authors.
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
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.google.common.io.Resources;
import com.google.common.net.MediaType;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.Glowroot;
import org.glowroot.common.util.Clock;
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
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MINUTES;

public class CommonHandler {

    private static final Logger logger = LoggerFactory.getLogger(CommonHandler.class);
    private static final Logger auditLogger = LoggerFactory.getLogger("audit");

    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final long TEN_YEARS = DAYS.toMillis(3650);
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

    // this constant is from org.h2.api.ErrorCode.STATEMENT_WAS_CANCELED
    // (but h2 jar is not a dependency of glowroot-ui)
    private static final int H2_STATEMENT_WAS_CANCELED = 57014;

    static {
        // not getting url for directory itself because proguard strips directory entries by default
        URL resourceBaseUrl = getUrlForPath(RESOURCE_BASE + "/index.html");
        if (resourceBaseUrl == null) {
            RESOURCE_BASE_URL_PREFIX = null;
        } else {
            String externalForm = resourceBaseUrl.toExternalForm();
            RESOURCE_BASE_URL_PREFIX =
                    externalForm.substring(0, externalForm.length() - "/index.html".length());
        }
    }

    private final boolean central;
    private final LayoutService layoutService;
    private final ImmutableMap<Pattern, HttpService> httpServices;
    private final ImmutableList<JsonServiceMapping> jsonServiceMappings;
    private final HttpSessionManager httpSessionManager;
    private final Clock clock;

    CommonHandler(boolean central, LayoutService layoutService,
            Map<Pattern, HttpService> httpServices, HttpSessionManager httpSessionManager,
            List<Object> jsonServices, Clock clock) {
        this.central = central;
        this.layoutService = layoutService;
        this.httpServices = ImmutableMap.copyOf(httpServices);
        this.httpSessionManager = httpSessionManager;
        this.clock = clock;
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
    }

    public CommonResponse handle(CommonRequest request) throws Exception {
        logger.debug("handleRequest(): path={}", request.getPath());
        CommonResponse response = handleIfLoginOrLogoutRequest(request);
        if (response != null) {
            return response;
        }
        boolean autoRefresh = isAutoRefresh(request.getParameters("auto-refresh"));
        boolean touchSession = !autoRefresh && !request.getPath().equals("/backend/layout");
        Authentication authentication =
                httpSessionManager.getAuthentication(request, touchSession);
        Glowroot.setTransactionUser(authentication.caseAmbiguousUsername());
        // need to grab agent-rollup-id up here before it is removed from query parameters
        String agentRollupId = getAgentRollupIdFromRequest(request);
        response = handleRequest(request, authentication);
        if (request.getPath().startsWith("/backend/")) {
            if (!request.getPath().equals("/backend/layout")) {
                response.setHeader("Glowroot-Layout-Version",
                        layoutService.getLayoutVersion(authentication));
            }
            if (!request.getPath().equals("/backend/agent-rollup-layout")
                    && agentRollupId != null) {
                String agentRollupLayoutVersion = layoutService
                        .getAgentRollupLayoutVersion(authentication, agentRollupId);
                if (agentRollupLayoutVersion != null) {
                    response.setHeader("Glowroot-Agent-Rollup-Layout-Version",
                            agentRollupLayoutVersion);
                }
            }
        }
        return response;
    }

    private @Nullable CommonResponse handleIfLoginOrLogoutRequest(CommonRequest request)
            throws Exception {
        String path = request.getPath();
        if (path.equals("/backend/login")) {
            String content = request.getContent();
            Credentials credentials = mapper.readValue(content, ImmutableCredentials.class);
            Glowroot.setTransactionUser(credentials.username());
            return httpSessionManager.login(credentials.username(), credentials.password());
        }
        if (path.equals("/backend/sign-out")) {
            httpSessionManager.signOut(request);
            Authentication authentication = httpSessionManager.getAnonymousAuthentication();
            Glowroot.setTransactionUser(authentication.caseAmbiguousUsername());
            String anonymousLayout = layoutService.getLayoutJson(authentication);
            CommonResponse response = new CommonResponse(OK, MediaType.JSON_UTF_8, anonymousLayout);
            httpSessionManager.deleteSessionCookie(response);
            return response;
        }
        if (path.equals("/backend/check-layout")) {
            Authentication authentication = httpSessionManager.getAuthentication(request, false);
            CommonResponse response = new CommonResponse(OK);
            response.setHeader("Glowroot-Layout-Version",
                    layoutService.getLayoutVersion(authentication));
            List<String> agentRollupIds = request.getParameters("agent-rollup-id");
            if (agentRollupIds != null && agentRollupIds.size() == 1) {
                String agentRollupLayoutVersion = layoutService
                        .getAgentRollupLayoutVersion(authentication, agentRollupIds.get(0));
                if (agentRollupLayoutVersion != null) {
                    response.setHeader("Glowroot-Agent-Rollup-Layout-Version",
                            agentRollupLayoutVersion);
                }
            }
            return response;
        }
        if (path.equals("/backend/layout")) {
            Authentication authentication = httpSessionManager.getAuthentication(request, false);
            return new CommonResponse(OK, MediaType.JSON_UTF_8,
                    layoutService.getLayoutJson(authentication));
        }
        if (path.equals("/backend/agent-rollup-layout")) {
            Authentication authentication = httpSessionManager.getAuthentication(request, false);
            String agentRollupId = request.getParameters("agent-rollup-id").get(0);
            return new CommonResponse(OK, MediaType.JSON_UTF_8,
                    layoutService.getAgentRollupLayoutJson(agentRollupId, authentication));
        }
        return null;
    }

    private CommonResponse handleRequest(CommonRequest request, Authentication authentication)
            throws Exception {
        String path = request.getPath();
        HttpService httpService = getHttpService(path);
        if (httpService != null) {
            return handleHttpService(request, httpService, authentication);
        }
        JsonServiceMapping jsonServiceMapping = getJsonServiceMapping(request, path);
        if (jsonServiceMapping != null) {
            return handleJsonServiceMappings(request, jsonServiceMapping, authentication);
        }
        return handleStaticResource(path, request);
    }

    private @Nullable HttpService getHttpService(String path) {
        for (Entry<Pattern, HttpService> entry : httpServices.entrySet()) {
            Matcher matcher = entry.getKey().matcher(path);
            if (matcher.matches()) {
                return entry.getValue();
            }
        }
        return null;
    }

    private CommonResponse handleHttpService(CommonRequest request, HttpService httpService,
            Authentication authentication) throws Exception {
        String permission = httpService.getPermission();
        if (permission.isEmpty()) {
            // service does not require any permission
            return httpService.handleRequest(request, authentication);
        }
        List<String> agentRollupIds = request.getParameters("agent-rollup-id");
        String agentRollupId = agentRollupIds.isEmpty() ? "" : agentRollupIds.get(0);
        if (!authentication.isPermitted(agentRollupId, permission)) {
            return handleNotAuthorized(request, authentication);
        }
        return httpService.handleRequest(request, authentication);
    }

    private @Nullable JsonServiceMapping getJsonServiceMapping(CommonRequest request,
            String path) {
        for (JsonServiceMapping jsonServiceMapping : jsonServiceMappings) {
            if (!jsonServiceMapping.httpMethod().name().equals(request.getMethod())) {
                continue;
            }
            if (jsonServiceMapping.path().equals(path)) {
                return jsonServiceMapping;
            }
        }
        return null;
    }

    private CommonResponse handleJsonServiceMappings(CommonRequest request,
            JsonServiceMapping jsonServiceMapping, Authentication authentication) throws Exception {
        List<Class<?>> parameterTypes = Lists.newArrayList();
        List<Object> parameters = Lists.newArrayList();
        Map<String, List<String>> queryParameters = request.getParameters();
        boolean permitted;
        if (jsonServiceMapping.bindAgentId()) {
            List<String> values = queryParameters.get("agent-id");
            if (values == null) {
                throw new JsonServiceException(BAD_REQUEST, "missing agent-id query parameter");
            }
            String agentId = values.get(0);
            if (central && agentId.isEmpty()) {
                throw new JsonServiceException(BAD_REQUEST, "agent-id query parameter is empty");
            }
            if (agentId.endsWith("::")) {
                throw new JsonServiceException(BAD_REQUEST,
                        "agent rollup id received when expecting an agent id");
            }
            parameterTypes.add(String.class);
            parameters.add(agentId);
            queryParameters.remove("agent-id");
            permitted = authentication.isPermittedForAgentRollup(agentId,
                    jsonServiceMapping.permission());
        } else if (jsonServiceMapping.bindAgentRollup()) {
            List<String> agentRollupIds = queryParameters.get("agent-rollup-id");
            if (agentRollupIds == null) {
                throw new JsonServiceException(BAD_REQUEST,
                        "missing agent-rollup-id query parameter");
            }
            String agentRollupId = agentRollupIds.get(0);
            parameterTypes.add(String.class);
            parameters.add(agentRollupId);
            queryParameters.remove("agent-rollup-id");
            permitted =
                    authentication.isPermittedForAgentRollup(agentRollupId,
                            jsonServiceMapping.permission());
        } else {
            permitted = jsonServiceMapping.permission().isEmpty()
                    || authentication.isAdminPermitted(jsonServiceMapping.permission());
        }
        if (!permitted) {
            return handleNotAuthorized(request, authentication);
        }
        Object responseObject;
        try {
            responseObject = callMethod(jsonServiceMapping, parameterTypes, parameters,
                    queryParameters, authentication, request);
        } catch (Exception e) {
            return newHttpResponseFromException(request, authentication, e);
        }
        return buildJsonResponse(responseObject);
    }

    CommonResponse newHttpResponseFromException(CommonRequest request,
            Authentication authentication, Exception exception) throws Exception {
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
            if (jsonServiceException.getStatus() == FORBIDDEN) {
                return handleNotAuthorized(request, authentication);
            } else {
                return newHttpResponseWithMessage(jsonServiceException.getStatus(),
                        jsonServiceException.getMessage());
            }
        }
        logger.error(e.getMessage(), e);
        if (e instanceof SQLException
                && ((SQLException) e).getErrorCode() == H2_STATEMENT_WAS_CANCELED) {
            return newHttpResponseWithMessage(REQUEST_TIMEOUT,
                    "Query timed out (timeout is configurable under Configuration > Advanced)");
        }
        return newHttpResponseWithStackTrace(e, INTERNAL_SERVER_ERROR, null);
    }

    private CommonResponse handleNotAuthorized(CommonRequest request,
            Authentication authentication) throws Exception {
        if (authentication.anonymous()) {
            if (httpSessionManager.getSessionId(request) != null) {
                return new CommonResponse(UNAUTHORIZED, MediaType.JSON_UTF_8,
                        "{\"timedOut\":true}");
            } else {
                return new CommonResponse(UNAUTHORIZED);
            }
        } else {
            return new CommonResponse(FORBIDDEN);
        }
    }

    private CommonResponse handleStaticResource(String path, CommonRequest request)
            throws IOException {
        URL url = getSecureUrlForPath(RESOURCE_BASE + path);
        if (url == null) {
            // log at debug only since this is typically just exploit bot spam
            logger.debug("unexpected path: {}", path);
            return new CommonResponse(NOT_FOUND);
        }
        Date expires = getExpiresForPath(path);
        if (request.getHeader(HttpHeaderNames.IF_MODIFIED_SINCE) != null && expires == null) {
            // all static resources without explicit expires are versioned and can be safely
            // cached forever
            return new CommonResponse(NOT_MODIFIED);
        }
        int extensionStartIndex = path.lastIndexOf('.');
        checkState(extensionStartIndex != -1, "found path under %s with no extension: %s",
                RESOURCE_BASE, path);
        String extension = path.substring(extensionStartIndex + 1);
        MediaType mediaType = mediaTypes.get(extension);
        checkNotNull(mediaType, "found extension under %s with no media type: %s", RESOURCE_BASE,
                extension);
        CommonResponse response = new CommonResponse(OK, mediaType, url);
        if (expires != null) {
            response.setHeader(HttpHeaderNames.EXPIRES, expires);
        } else {
            response.setHeader(HttpHeaderNames.LAST_MODIFIED, new Date(0));
            response.setHeader(HttpHeaderNames.EXPIRES,
                    new Date(clock.currentTimeMillis() + TEN_YEARS));
        }
        return response;
    }

    private @Nullable Date getExpiresForPath(String path) {
        if (path.startsWith("org/glowroot/ui/app-dist/favicon.")) {
            return new Date(clock.currentTimeMillis() + ONE_DAY);
        } else if (path.endsWith(".js.map") || path.startsWith("/sources/")) {
            // javascript source maps and source files are not versioned
            return new Date(clock.currentTimeMillis() + FIVE_MINUTES);
        } else {
            return null;
        }
    }

    private static @Nullable String getAgentRollupIdFromRequest(CommonRequest request) {
        List<String> agentIds = request.getParameters("agent-id");
        if (agentIds != null && agentIds.size() == 1) {
            return agentIds.get(0);
        }
        List<String> agentRollupIds = request.getParameters("agent-rollup-id");
        if (agentRollupIds != null && agentRollupIds.size() == 1) {
            return agentRollupIds.get(0);
        }
        return null;
    }

    private static CommonResponse buildJsonResponse(@Nullable Object responseObject) {
        if (responseObject == null) {
            return new CommonResponse(OK, MediaType.JSON_UTF_8, "");
        } else if (responseObject instanceof CommonResponse) {
            return (CommonResponse) responseObject;
        } else if (responseObject instanceof String) {
            return new CommonResponse(OK, MediaType.JSON_UTF_8, (String) responseObject);
        } else {
            logger.warn("unexpected type of json service response: {}",
                    responseObject.getClass().getName());
            return new CommonResponse(INTERNAL_SERVER_ERROR);
        }
    }

    private static JsonServiceMapping build(HttpMethod httpMethod, String path,
            String permission, Object jsonService, Method method) {
        boolean bindAgentId = false;
        boolean bindAgentRollup = false;
        Class<?> bindRequest = null;
        boolean bindAutoRefresh = false;
        boolean bindAuthentication = false;
        for (int i = 0; i < method.getParameterAnnotations().length; i++) {
            Annotation[] parameterAnnotations = method.getParameterAnnotations()[i];
            for (Annotation annotation : parameterAnnotations) {
                if (annotation.annotationType() == BindAgentId.class) {
                    bindAgentId = true;
                } else if (annotation.annotationType() == BindAgentRollupId.class) {
                    bindAgentRollup = true;
                } else if (annotation.annotationType() == BindRequest.class) {
                    bindRequest = method.getParameterTypes()[i];
                } else if (annotation.annotationType() == BindAutoRefresh.class) {
                    bindAutoRefresh = true;
                } else if (annotation.annotationType() == BindAuthentication.class) {
                    bindAuthentication = true;
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
                .bindAutoRefresh(bindAutoRefresh)
                .bindAuthentication(bindAuthentication)
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

    private static CommonResponse newHttpResponseWithMessage(HttpResponseStatus status,
            @Nullable String message) throws IOException {
        // this is an "expected" exception, no need to send back stack trace
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            jg.writeStringField("message", message);
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return new CommonResponse(status, MediaType.JSON_UTF_8, sb.toString());
    }

    static CommonResponse newHttpResponseWithStackTrace(Exception e,
            HttpResponseStatus status, @Nullable String simplifiedMessage) throws IOException {
        return new CommonResponse(status, MediaType.JSON_UTF_8,
                getHttpResponseWithStackTrace(e, simplifiedMessage));
    }

    private static String getHttpResponseWithStackTrace(Exception e,
            @Nullable String simplifiedMessage) throws IOException {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
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
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    private static @Nullable Object callMethod(JsonServiceMapping jsonServiceMapping,
            List<Class<?>> parameterTypes, List<Object> parameters,
            Map<String, List<String>> queryParameters, Authentication authentication,
            CommonRequest request) throws Exception {
        List<String> autoRefreshParams = queryParameters.remove("auto-refresh");
        boolean autoRefresh = isAutoRefresh(autoRefreshParams);
        Class<?> bindRequest = jsonServiceMapping.bindRequest();
        if (bindRequest != null) {
            parameterTypes.add(bindRequest);
            if (jsonServiceMapping.httpMethod() == HttpMethod.GET) {
                parameters.add(QueryStrings.decode(queryParameters, bindRequest));
            } else {
                String content = request.getContent();
                auditLogger.info("{} - POST {} - {}", authentication.caseAmbiguousUsername(),
                        request.getUri(), content);
                if (bindRequest == String.class) {
                    parameters.add(content);
                } else {
                    Object param =
                            mapper.readValue(content, QueryStrings.getImmutableClass(bindRequest));
                    checkNotNull(param);
                    parameters.add(param);
                }
            }
        }
        if (jsonServiceMapping.bindAutoRefresh()) {
            parameterTypes.add(boolean.class);
            parameters.add(autoRefresh);
        }
        if (jsonServiceMapping.bindAuthentication()) {
            parameterTypes.add(Authentication.class);
            parameters.add(authentication);
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

    private static boolean isAutoRefresh(@Nullable List<String> autoRefreshParams) {
        return autoRefreshParams != null && autoRefreshParams.size() == 1
                && Boolean.valueOf(autoRefreshParams.get(0));
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
        boolean bindAutoRefresh();
        boolean bindAuthentication();
    }

    enum HttpMethod {
        GET, POST
    }

    public interface CommonRequest {

        String getMethod();

        // includes context path
        String getUri();

        String getContextPath();

        // does not include context path
        String getPath();

        @Nullable
        String getHeader(CharSequence name);

        Map<String, List<String>> getParameters();

        List<String> getParameters(String name);

        String getContent() throws IOException;
    }

    public static class CommonResponse {

        private final HttpResponseStatus status;
        private final HttpHeaders headers = new DefaultHttpHeaders();
        private final Object content;

        private @Nullable String zipFileName;
        private boolean closeConnectionAfterPortChange;

        CommonResponse(HttpResponseStatus status, MediaType mediaType, String content) {
            this(status, mediaType, Unpooled.copiedBuffer(content, Charsets.UTF_8), true);
        }

        CommonResponse(HttpResponseStatus status, MediaType mediaType, ChunkSource content) {
            this(status, mediaType, content, true);
        }

        CommonResponse(HttpResponseStatus status) {
            this(status, null, Unpooled.buffer(0), true);
        }

        private CommonResponse(HttpResponseStatus status, MediaType mediaType, URL url)
                throws IOException {
            this(status, mediaType, Unpooled.copiedBuffer(Resources.toByteArray(url)), false);
        }

        private CommonResponse(HttpResponseStatus status, @Nullable MediaType mediaType,
                Object content, boolean preventCaching) {
            this.status = status;
            this.content = content;
            if (mediaType != null) {
                headers.set(HttpHeaderNames.CONTENT_TYPE, mediaType);
            }
            if (preventCaching) {
                // prevent caching of dynamic json data, using 'definitive' minimum set of headers
                // from http://stackoverflow.com/questions/49547/
                // making-sure-a-web-page-is-not-cached-across-all-browsers/2068407#2068407
                headers.set(HttpHeaderNames.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
                headers.set(HttpHeaderNames.PRAGMA, "no-cache");
                headers.set(HttpHeaderNames.EXPIRES, new Date(0));
            }
        }

        void setHeader(CharSequence name, Object value) {
            headers.set(name, value);
        }

        void setZipFileName(String zipFileName) {
            this.zipFileName = zipFileName;
        }

        void setCloseConnectionAfterPortChange() {
            closeConnectionAfterPortChange = true;
        }

        public HttpResponseStatus getStatus() {
            return status;
        }

        public HttpHeaders getHeaders() {
            return headers;
        }

        // returns ByteBuf or ChunkSource
        public Object getContent() {
            return content;
        }

        public @Nullable String getZipFileName() {
            return zipFileName;
        }

        boolean isCloseConnectionAfterPortChange() {
            return closeConnectionAfterPortChange;
        }
    }
}
