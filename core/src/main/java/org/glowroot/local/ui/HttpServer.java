/*
 * Copyright 2011-2014 the original author or authors.
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

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelException;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioServerBossPool;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioWorkerPool;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.logging.Slf4JLoggerFactory;
import org.jboss.netty.util.ThreadNameDeterminer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(HttpServer.class);

    private final ServerBootstrap bootstrap;
    private final HttpServerHandler handler;

    private volatile Channel serverChannel;
    private volatile int port;

    HttpServer(String bindAddress, int port, int numWorkerThreads,
            LayoutJsonService layoutJsonService, ImmutableMap<Pattern, Object> uriMappings,
            HttpSessionManager httpSessionManager, List<Object> jsonServices) {

        InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());

        ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true).build();
        ExecutorService bossExecutor = Executors.newCachedThreadPool(threadFactory);
        ExecutorService workerExecutor = Executors.newCachedThreadPool(threadFactory);
        PrefixingThreadNameDeterminer determiner = new PrefixingThreadNameDeterminer();
        bootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(
                new NioServerBossPool(bossExecutor, 1, determiner),
                new NioWorkerPool(workerExecutor, numWorkerThreads, determiner)));

        final HttpServerHandler handler = new HttpServerHandler(layoutJsonService, uriMappings,
                httpSessionManager, jsonServices);
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() {
                ChannelPipeline pipeline = Channels.pipeline();
                pipeline.addLast("decoder", new HttpRequestDecoder());
                pipeline.addLast("aggregator", new HttpChunkAggregator(65536));
                pipeline.addLast("encoder", new HttpResponseEncoder());
                pipeline.addLast("deflater", new ConditionalHttpContentCompressor());
                pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
                pipeline.addLast("handler", handler);
                return pipeline;
            }
        });
        this.handler = handler;
        InetSocketAddress localAddress = new InetSocketAddress(bindAddress, port);
        logger.debug("<init>(): binding http server to port {}", port);
        Channel serverChannel;
        try {
            serverChannel = bootstrap.bind(localAddress);
        } catch (ChannelException e) {
            serverChannel = bootstrap.bind(new InetSocketAddress(0));
            logger.error("error binding to port: {} (bound to port {} instead)", port,
                    ((InetSocketAddress) serverChannel.getLocalAddress()).getPort());
            // log stack trace at debug level
            logger.debug(e.getMessage(), e);
        }
        this.serverChannel = serverChannel;
        this.port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        logger.debug("<init>(): http server bound");
    }

    int getPort() {
        return port;
    }

    void changePort(final int newPort) throws PortChangeFailedException {
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
        } catch (InterruptedException e) {
            throw new PortChangeFailedException(e);
        } catch (ExecutionException e) {
            throw new PortChangeFailedException(e);
        } finally {
            executor.shutdownNow();
        }
        previousServerChannel.close();
        handler.closeAllButCurrent();
    }

    void close() {
        logger.debug("close(): stopping http server");
        serverChannel.close().awaitUninterruptibly();
        handler.close();
        logger.debug("close(): http server stopped");
        bootstrap.releaseExternalResources();
    }

    private class ChangePort implements Runnable {

        private final int newPort;

        ChangePort(int newPort) {
            this.newPort = newPort;
        }

        @Override
        public void run() {
            InetSocketAddress localAddress = new InetSocketAddress(newPort);
            HttpServer.this.serverChannel = bootstrap.bind(localAddress);
            HttpServer.this.port = newPort;
        }
    }

    @SuppressWarnings("serial")
    public static class PortChangeFailedException extends Exception {
        private PortChangeFailedException(Exception cause) {
            super(cause);
        }
    }

    private static class PrefixingThreadNameDeterminer implements ThreadNameDeterminer {

        private final AtomicInteger workerCount = new AtomicInteger();

        @Override
        public String determineThreadName(String currentThreadName, String proposedThreadName) {
            if (proposedThreadName.matches("New I/O server boss #[0-9]+")) {
                // leave off the # since there is always a single boss thread
                return "Glowroot-Http-Boss";
            }
            if (proposedThreadName.matches("New I/O worker #[0-9]+")) {
                // use separate worker specific counter since netty # is shared between bosses
                // and workers (and netty # starts at 1 while other glowroot thread pools start
                // numbering at 0)
                return "Glowroot-Http-Worker-" + workerCount.getAndIncrement();
            }
            logger.warn("unexpected thread name: {}", proposedThreadName);
            return proposedThreadName;
        }
    }
}
