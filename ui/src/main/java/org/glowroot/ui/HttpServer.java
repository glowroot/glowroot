/*
 * Copyright 2011-2016 the original author or authors.
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

import java.io.File;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.util.Clock;

import static java.util.concurrent.TimeUnit.SECONDS;

class HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(HttpServer.class);

    private final ServerBootstrap bootstrap;
    private final HttpServerHandler handler;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;

    private final String bindAddress;
    private final File baseDir;

    private volatile @Nullable SslContext sslContext;
    private volatile Channel serverChannel;
    private volatile int port;

    HttpServer(String bindAddress, int port, int numWorkerThreads, LayoutService layoutService,
            ConfigRepository configRepository, Map<Pattern, HttpService> httpServices,
            HttpSessionManager httpSessionManager, List<Object> jsonServices, File baseDir,
            Clock clock) throws Exception {

        InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);

        ThreadFactory bossThreadFactory = new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("Glowroot-Http-Boss")
                .build();
        ThreadFactory workerThreadFactory = new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("Glowroot-Http-Worker-%d")
                .build();
        bossGroup = new NioEventLoopGroup(1, bossThreadFactory);
        workerGroup = new NioEventLoopGroup(numWorkerThreads, workerThreadFactory);

        final HttpServerHandler handler = new HttpServerHandler(layoutService, configRepository,
                httpServices, httpSessionManager, jsonServices, clock);

        if (configRepository.getWebConfig().https()) {
            sslContext = SslContextBuilder
                    .forServer(new File(baseDir, "certificate.pem"),
                            new File(baseDir, "private.pem"))
                    .build();
        }
        this.baseDir = baseDir;

        bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        SslContext sslContextLocal = sslContext;
                        if (sslContextLocal != null) {
                            p.addLast(sslContextLocal.newHandler(ch.alloc()));
                        }
                        // bumping maxInitialLineLength (first arg below) from default 4096 to 32768
                        // in order to handle long urls on /jvm/gauges view
                        // bumping maxHeaderSize (second arg below) from default 8192 to 32768 for
                        // same reason due to "Referer" header once url becomes huge
                        // leaving maxChunkSize (third arg below) at default 8192
                        p.addLast(new HttpServerCodec(32768, 32768, 8192));
                        p.addLast(new HttpObjectAggregator(1048576));
                        p.addLast(new ConditionalHttpContentCompressor());
                        p.addLast(new ChunkedWriteHandler());
                        p.addLast(handler);
                    }
                });
        this.handler = handler;
        logger.debug("<init>(): binding http server to port {}", port);
        this.bindAddress = bindAddress;
        Channel serverChannel;
        try {
            serverChannel =
                    bootstrap.bind(new InetSocketAddress(bindAddress, port)).sync().channel();
        } catch (Exception e) {
            // FailedChannelFuture.sync() is using UNSAFE to re-throw checked exceptions
            bossGroup.shutdownGracefully(0, 0, SECONDS);
            workerGroup.shutdownGracefully(0, 0, SECONDS);
            throw new SocketBindException(e);
        }
        this.serverChannel = serverChannel;
        this.port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        logger.debug("<init>(): http server bound");
    }

    String getBindAddress() {
        return bindAddress;
    }

    int getPort() {
        return port;
    }

    boolean getHttps() {
        return sslContext != null;
    }

    void changePort(int newPort) throws PortChangeFailedException {
        // need to call from separate thread, since netty throws exception if I/O thread (serving
        // http request) calls awaitUninterruptibly(), which is called by bind() below
        Channel previousServerChannel = this.serverChannel;
        ChangePort changePort = new ChangePort(newPort);
        ThreadFactory threadFactory = new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("Glowroot-Temporary-Thread")
                .build();
        ExecutorService executor = Executors.newSingleThreadExecutor(threadFactory);
        try {
            // calling get() will wait until ChangePort is complete and will re-throw any exceptions
            // thrown by ChangePort
            executor.submit(changePort).get();
        } catch (Exception e) {
            throw new PortChangeFailedException(e);
        } finally {
            executor.shutdown();
        }
        previousServerChannel.close();
        handler.closeAllButCurrent();
    }

    void changeProtocol(boolean ssl) throws Exception {
        if (ssl) {
            sslContext = SslContextBuilder
                    .forServer(new File(baseDir, "certificate.pem"),
                            new File(baseDir, "private.pem"))
                    .build();
        } else {
            sslContext = null;
        }
        handler.closeAllButCurrent();
    }

    // used by tests and by central ui
    void close(boolean waitForChannelClose) {
        logger.debug("close(): stopping http server");
        if (waitForChannelClose) {
            serverChannel.close().awaitUninterruptibly();
        } else {
            serverChannel.close().awaitUninterruptibly(1, SECONDS);
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        handler.close(waitForChannelClose);
        logger.debug("close(): http server stopped");
    }

    private class ChangePort implements Callable</*@Nullable*/ Void> {

        private final int newPort;

        ChangePort(int newPort) {
            this.newPort = newPort;
        }

        @Override
        public @Nullable Void call() throws InterruptedException {
            InetSocketAddress localAddress = new InetSocketAddress(bindAddress, newPort);
            HttpServer.this.serverChannel = bootstrap.bind(localAddress).sync().channel();
            HttpServer.this.port = newPort;
            return null;
        }
    }

    @SuppressWarnings("serial")
    static class SocketBindException extends Exception {
        private SocketBindException(Exception cause) {
            super(cause);
        }
    }

    @SuppressWarnings("serial")
    static class PortChangeFailedException extends Exception {
        private PortChangeFailedException(Exception cause) {
            super(cause);
        }
    }
}
