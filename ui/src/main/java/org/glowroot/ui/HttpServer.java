/*
 * Copyright 2011-2017 the original author or authors.
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

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
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.repo.ConfigRepository;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

class HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(HttpServer.class);

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private final ServerBootstrap bootstrap;
    private final HttpServerHandler handler;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;

    private final String bindAddress;
    private final File certificateDir;

    private volatile @Nullable SslContext sslContext;
    private volatile @MonotonicNonNull Channel serverChannel;
    private volatile @MonotonicNonNull Integer port;

    HttpServer(String bindAddress, int numWorkerThreads, ConfigRepository configRepository,
            CommonHandler commonHandler, File certificateDir) throws Exception {

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

        final HttpServerHandler handler = new HttpServerHandler(configRepository, commonHandler);

        if (configRepository.getWebConfig().https()) {
            sslContext = SslContextBuilder
                    .forServer(new File(certificateDir, "certificate.pem"),
                            new File(certificateDir, "private.pem"))
                    .build();
        }
        this.certificateDir = certificateDir;

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
        this.bindAddress = bindAddress;
    }

    void bindEventually(int port) {
        try {
            serverChannel =
                    bootstrap.bind(new InetSocketAddress(bindAddress, port)).sync().channel();
            onBindSuccess();
        } catch (Exception e) {
            // FailedChannelFuture.sync() is using UNSAFE to re-throw checked exceptions
            logger.debug(e.getMessage(), e);
            startupLogger.error("Error binding to {}:{}, the UI will not be available"
                    + " (will keep trying to bind): {}", bindAddress, port, e.getMessage());
            ThreadFactory threadFactory = new ThreadFactoryBuilder()
                    .setDaemon(true)
                    .setNameFormat("Glowroot-Init")
                    .build();
            Executors.newSingleThreadExecutor(threadFactory).execute(new BindEventually(port));
        }
    }

    @RequiresNonNull("serverChannel")
    private void onBindSuccess() {
        port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        if (bindAddress.equals("127.0.0.1")) {
            startupLogger.info("UI listening on {}:{} (to access the UI from remote machines,"
                    + " change the bind address to 0.0.0.0, either in the Glowroot UI under"
                    + " Configuration > Web or directly in the admin.json file, and then restart"
                    + " JVM to take effect)", bindAddress, port);
        } else {
            startupLogger.info("UI listening on {}:{}", bindAddress, port);
        }
    }

    String getBindAddress() {
        return bindAddress;
    }

    @Nullable
    Integer getPort() {
        return port;
    }

    boolean getHttps() {
        return sslContext != null;
    }

    void changePort(int newPort) throws PortChangeFailedException {
        checkNotNull(serverChannel);
        // need to call from separate thread, since netty throws exception if I/O thread (serving
        // http request) calls awaitUninterruptibly(), which is called by bind() below
        Channel previousServerChannel = serverChannel;
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
                    .forServer(new File(certificateDir, "certificate.pem"),
                            new File(certificateDir, "private.pem"))
                    .build();
        } else {
            sslContext = null;
        }
        handler.closeAllButCurrent();
    }

    // used by tests and by central ui
    void close(boolean waitForChannelClose) {
        logger.debug("close(): stopping http server");
        if (serverChannel != null) {
            if (waitForChannelClose) {
                serverChannel.close().awaitUninterruptibly();
            } else {
                serverChannel.close().awaitUninterruptibly(1, SECONDS);
            }
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        handler.close(waitForChannelClose);
        logger.debug("close(): http server stopped");
    }

    private class BindEventually implements Runnable {

        private final int port;

        private BindEventually(int port) {
            this.port = port;
        }

        @Override
        public void run() {
            long backoffMillis = 100;
            while (true) {
                try {
                    serverChannel = bootstrap.bind(new InetSocketAddress(bindAddress, port)).sync()
                            .channel();
                    onBindSuccess();
                    return;
                } catch (Exception e) {
                    // FailedChannelFuture.sync() is using UNSAFE to re-throw checked exceptions
                    logger.debug(e.getMessage(), e);
                    try {
                        Thread.sleep(backoffMillis);
                    } catch (InterruptedException f) {
                        Thread.interrupted();
                        return;
                    }
                    backoffMillis = Math.min(backoffMillis * 2, 10000);
                }
            }
        }
    }

    private class ChangePort implements Callable</*@Nullable*/ Void> {

        private final int newPort;

        ChangePort(int newPort) {
            this.newPort = newPort;
        }

        @Override
        public @Nullable Void call() throws InterruptedException {
            InetSocketAddress localAddress = new InetSocketAddress(bindAddress, newPort);
            serverChannel = bootstrap.bind(localAddress).sync().channel();
            port = newPort;
            return null;
        }
    }

    @SuppressWarnings("serial")
    static class PortChangeFailedException extends Exception {
        private PortChangeFailedException(Exception cause) {
            super(cause);
        }
    }
}
