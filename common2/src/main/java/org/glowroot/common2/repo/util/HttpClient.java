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
package org.glowroot.common2.repo.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;

import com.google.common.base.MoreObjects;
import com.google.common.base.Throwables;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common2.config.HttpProxyConfig;
import org.glowroot.common2.repo.ConfigRepository;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

public class HttpClient {

    private static final Logger logger = LoggerFactory.getLogger(HttpClient.class);

    private final ConfigRepository configRepository;

    public HttpClient(ConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    public void get(String url) throws Exception {
        postOrGet(url, null, null, configRepository.getHttpProxyConfig(), null);
    }

    // optional passwordOverride can be passed in to test HTTP proxy from
    // AdminJsonService.sentTestHttpProxyRequest() without possibility of throwing
    // org.glowroot.common.repo.util.LazySecretKey.SymmetricEncryptionKeyMissingException
    public String getWithHttpProxyConfigOverride(String url,
            HttpProxyConfig httpProxyConfig, @Nullable String passwordOverride) throws Exception {
        return postOrGet(url, null, null, httpProxyConfig, passwordOverride);
    }

    void post(String url, byte[] content, String contentType) throws Exception {
        postOrGet(url, content, contentType, configRepository.getHttpProxyConfig(), null);
    }

    private String postOrGet(String url, byte /*@Nullable*/ [] content,
            @Nullable String contentType, final HttpProxyConfig httpProxyConfig,
            final @Nullable String passwordOverride) throws Exception {
        URI uri = new URI(url);
        String scheme = checkNotNull(uri.getScheme());
        final boolean ssl = scheme.equalsIgnoreCase("https");
        final String host = checkNotNull(uri.getHost());
        final int port;
        if (uri.getPort() == -1) {
            port = ssl ? 443 : 80;
        } else {
            port = uri.getPort();
        }
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap bootstrap = new Bootstrap();
            // TODO follow netty proxy support at https://github.com/netty/netty/issues/1133
            final HttpProxyHandler httpProxyHandler =
                    newHttpProxyHandlerIfNeeded(httpProxyConfig, passwordOverride);
            final HttpClientHandler handler = new HttpClientHandler();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline p = ch.pipeline();
                            if (httpProxyHandler != null) {
                                p.addLast(httpProxyHandler);
                            }
                            if (ssl) {
                                SslContext sslContext = SslContextBuilder.forClient().build();
                                p.addLast(sslContext.newHandler(ch.alloc(), host, port));
                            }
                            p.addLast(new HttpClientCodec());
                            p.addLast(new HttpObjectAggregator(1048576));
                            p.addLast(handler);
                        }
                    });
            if (!httpProxyConfig.host().isEmpty()) {
                // name resolution should be performed by the proxy server in case some proxy rules
                // depend on the remote hostname
                bootstrap.resolver(NoopAddressResolverGroup.INSTANCE);
            }
            HttpRequest request;
            if (content == null) {
                request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                        uri.getRawPath());
            } else {
                request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST,
                        uri.getRawPath(), Unpooled.wrappedBuffer(content));
                request.headers().set(HttpHeaderNames.CONTENT_TYPE, checkNotNull(contentType));
                request.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
            }
            request.headers().set(HttpHeaderNames.HOST, host);
            request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            Channel ch = bootstrap.connect(host, port).sync().channel();
            if (httpProxyHandler != null) {
                // this line is needed to capture and throw connection exception properly
                httpProxyHandler.connectFuture().get();
            }
            ch.writeAndFlush(request).get();
            ch.closeFuture().sync().get();
            Throwable exception = handler.exception;
            if (exception != null) {
                Throwables.propagateIfPossible(exception, Exception.class);
                throw new Exception(exception);
            }
            HttpResponseStatus responseStatus = checkNotNull(handler.responseStatus);
            int statusCode = responseStatus.code();
            if (statusCode == 429) {
                throw new TooManyRequestsHttpResponseException();
            } else if (statusCode < 200 || statusCode >= 300) {
                throw new IOException("Unexpected response status code: " + statusCode);
            }
            return checkNotNull(handler.responseContent);
        } finally {
            group.shutdownGracefully(0, 10, SECONDS).get();
        }
    }

    private @Nullable HttpProxyHandler newHttpProxyHandlerIfNeeded(HttpProxyConfig httpProxyConfig,
            @Nullable String passwordOverride) throws Exception {
        String proxyHost = httpProxyConfig.host();
        if (proxyHost.isEmpty()) {
            return null;
        }
        int proxyPort = MoreObjects.firstNonNull(httpProxyConfig.port(), 80);
        SocketAddress proxyAddress = new InetSocketAddress(proxyHost, proxyPort);
        String username = httpProxyConfig.username();
        if (username.isEmpty()) {
            return new HttpProxyHandler(proxyAddress);
        } else {
            String password = getPassword(httpProxyConfig, passwordOverride);
            return new HttpProxyHandler(proxyAddress, username, password);
        }
    }

    private String getPassword(HttpProxyConfig httpProxyConfig, @Nullable String passwordOverride)
            throws Exception {
        if (passwordOverride != null) {
            return passwordOverride;
        }
        String password = httpProxyConfig.encryptedPassword();
        if (password.isEmpty()) {
            return "";
        }
        return Encryption.decrypt(password, configRepository.getLazySecretKey());
    }

    private static class HttpClientHandler extends ChannelInboundHandlerAdapter {

        private volatile @MonotonicNonNull HttpResponseStatus responseStatus;
        private volatile @Nullable String responseContent;
        private volatile @Nullable Throwable exception;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof HttpResponse && msg instanceof HttpContent) {
                    responseStatus = ((HttpResponse) msg).status();
                    responseContent = ((HttpContent) msg).content().toString(CharsetUtil.UTF_8);
                } else {
                    exception =
                            new Exception("Unexpected response message class: " + msg.getClass());
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            logger.debug(cause.getMessage(), cause);
            exception = cause;
            ctx.close();
        }
    }

    @SuppressWarnings("serial")
    static class TooManyRequestsHttpResponseException extends Exception {}
}
