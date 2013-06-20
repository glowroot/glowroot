/**
 * Copyright 2013 the original author or authors.
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
package io.informant.local.ui;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.CaseFormat;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.ObjectArrays;
import com.google.common.io.Resources;
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
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.common.ObjectMappers;

import static org.jboss.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class HttpServerHandler extends SimpleChannelUpstreamHandler {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerHandler.class);
    private static final ObjectMapper mapper = ObjectMappers.create();
    private static final long TEN_YEARS = 10 * 365 * 24 * 60 * 60 * 1000L;
    private static final long FIVE_MINUTES = 5 * 60 * 1000L;

    private static final ImmutableSet<String> BROWSER_DISCONNECT_MESSAGES = ImmutableSet.of(
            "An existing connection was forcibly closed by the remote host",
            "An established connection was aborted by the software in your host machine");

    private final ChannelGroup allChannels;

    private final ImmutableMap<Pattern, Object> uriMappings;
    private final ImmutableList<JsonServiceMapping> jsonServiceMappings;

    private final boolean devMode;

    HttpServerHandler(ImmutableMap<Pattern, Object> uriMappings,
            ImmutableList<JsonServiceMapping> jsonServiceMappings, boolean devMode) {
        this.uriMappings = uriMappings;
        this.jsonServiceMappings = jsonServiceMappings;
        this.devMode = devMode;
        allChannels = new DefaultChannelGroup();
    }

    @Override
    public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) {
        logger.debug("channelOpen()");
        allChannels.add(e.getChannel());
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws IOException,
            InterruptedException {
        HttpRequest request = (HttpRequest) e.getMessage();
        logger.debug("messageReceived(): request.uri={}", request.getUri());
        HttpResponse response = handleRequest(request, e.getChannel());
        if (response == null) {
            // streaming response
            return;
        }
        boolean keepAlive = HttpHeaders.isKeepAlive(request);
        if (keepAlive) {
            // add content-length header only for keep-alive connections
            response.setHeader(Names.CONTENT_LENGTH, response.getContent().readableBytes());
        }
        logger.debug("messageReceived(): response={}", response);
        ChannelFuture f = e.getChannel().write(response);
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

    @Nullable
    private HttpResponse handleRequest(HttpRequest request, Channel channel) throws IOException {
        logger.debug("handleRequest(): request.uri={}", request.getUri());
        QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());
        String path = decoder.getPath();
        boolean fingerprinted = decoder.getParameters().containsKey("fingerprint");
        logger.debug("handleRequest(): path={}", path);
        for (Entry<Pattern, Object> uriMappingEntry : uriMappings.entrySet()) {
            Matcher matcher = uriMappingEntry.getKey().matcher(path);
            if (matcher.matches()) {
                if (uriMappingEntry.getValue() instanceof HttpService) {
                    return ((HttpService) uriMappingEntry.getValue())
                            .handleRequest(request, channel);
                } else {
                    // only other value type is String
                    String resourcePath = matcher.replaceFirst((String) uriMappingEntry.getValue());
                    if (resourcePath.matches("io/informant/local/(ui|ui-build)/[^/]*\\.html")) {
                        handleStaticHtmlPage(resourcePath, channel);
                        // return null to indicate streaming
                        return null;
                    }
                    return handleStaticResource(resourcePath, fingerprinted);
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

    private void handleStaticHtmlPage(String resourcePath, Channel channel) throws IOException {
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.setHeader(Names.CONTENT_TYPE, "text/html; charset=UTF-8");
        if (!devMode) {
            // cache for a few minutes
            response.setHeader(Names.EXPIRES, new Date(System.currentTimeMillis() + FIVE_MINUTES));
        }
        response.setChunked(true);
        channel.write(response);
        channel.write(new ReaderChunkedInput(HtmlPages.render(resourcePath, devMode).openStream()));
    }

    private HttpResponse handleStaticResource(String path, boolean fingerprinted)
            throws IOException {
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
        URL url;
        try {
            url = Resources.getResource(path);
        } catch (IllegalArgumentException e) {
            logger.warn("unexpected path '{}'", path);
            return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        if (extension.equals("html")) {
        }
        byte[] staticContent = Resources.toByteArray(url);
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.setContent(ChannelBuffers.copiedBuffer(staticContent));
        response.setHeader(Names.CONTENT_TYPE, mimeType);
        response.setHeader(Names.CONTENT_LENGTH, staticContent.length);
        if (path.matches("^io/informant/local/(ui|ui-build)/lib/.*$") || fingerprinted) {
            // these are all third-party versioned resources or fingerprinted resources and can be
            // safely cached forever
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
        } else if (extension.equals("less")) {
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

    private static HttpResponse handleJsonRequest(Object jsonService, String serviceMethodName,
            String[] args, String requestText) {

        logger.debug("handleJsonRequest(): serviceMethodName={}, args={}, requestText={}",
                serviceMethodName, args, requestText);
        Object responseText;
        try {
            responseText = callMethod(jsonService, serviceMethodName, args, requestText);
        } catch (SecurityException e) {
            logger.warn(e.getMessage(), e);
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        } catch (IllegalArgumentException e) {
            logger.warn(e.getMessage(), e);
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        } catch (NoSuchMethodException e) {
            logger.warn(e.getMessage(), e);
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        } catch (IllegalAccessException e) {
            logger.warn(e.getMessage(), e);
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        } catch (InvocationTargetException e) {
            logger.warn(e.getCause().getMessage(), e.getCause());
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        }
        HttpResponse response;
        if (responseText == null) {
            response = new DefaultHttpResponse(HTTP_1_1, OK);
            response.setContent(ChannelBuffers.EMPTY_BUFFER);
            HttpServices.preventCaching(response);
        } else if (responseText instanceof String) {
            response = new DefaultHttpResponse(HTTP_1_1, OK);
            response.setContent(ChannelBuffers.copiedBuffer(responseText.toString(),
                    Charsets.ISO_8859_1));
            response.setHeader(Names.CONTENT_TYPE, "application/json; charset=UTF-8");
            HttpServices.preventCaching(response);
        } else if (responseText instanceof byte[]) {
            response = new DefaultHttpResponse(HTTP_1_1, OK);
            response.setContent(ChannelBuffers.wrappedBuffer((byte[]) responseText));
            response.setHeader(Names.CONTENT_TYPE, "application/json; charset=UTF-8");
            HttpServices.preventCaching(response);
        } else {
            logger.warn("unexpected type of json service response '{}'", responseText.getClass()
                    .getName());
            response = new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        }
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
            Object[] argsWithOptional = ObjectArrays.concat(args, optionalArg);
            return method.invoke(object, argsWithOptional);
        } else {
            return method.invoke(object, args);
        }
    }

    private static Class<?>[] getParameterTypes(int length) {
        Class<?>[] parameterTypes = new Class<?>[length];
        Arrays.fill(parameterTypes, String.class);
        return parameterTypes;
    }

    private static String getRequestText(HttpRequest request, QueryStringDecoder decoder)
            throws JsonProcessingException {
        if (decoder.getParameters().isEmpty()) {
            return request.getContent().toString(Charsets.ISO_8859_1);
        } else {
            // create json message out of the query string
            // flatten map values from list to single element where possible
            Map<String, Object> parameters = Maps.newHashMap();
            for (Entry<String, List<String>> entry : decoder.getParameters().entrySet()) {
                String key = entry.getKey();
                key = CaseFormat.LOWER_HYPHEN.to(CaseFormat.LOWER_CAMEL, key);
                if (entry.getValue().size() == 1) {
                    parameters.put(key, entry.getValue().get(0));
                } else {
                    parameters.put(key, entry.getValue());
                }
            }
            return mapper.writeValueAsString(parameters);
        }
    }

    static class JsonServiceMapping {
        private final Pattern pattern;
        private final Object service;
        private final String methodName;
        JsonServiceMapping(String pattern, Object service, String methodName) {
            this.pattern = Pattern.compile(pattern);
            this.service = service;
            this.methodName = methodName;
        }
    }
}
