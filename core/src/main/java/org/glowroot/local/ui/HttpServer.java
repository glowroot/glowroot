/*
 * Copyright 2011-2013 the original author or authors.
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import checkers.nullness.quals.Nullable;
import com.google.common.collect.ImmutableList;
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
import org.jboss.netty.util.ThreadNameDeterminer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.Singleton;

/**
 * Handles all http requests for the embedded UI (by default http://localhost:4000).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(HttpServer.class);

    private final ServerBootstrap bootstrap;
    private final HttpServerHandler handler;

    private volatile Channel serverChannel;
    private volatile int port;

    HttpServer(int port, int numWorkerThreads, IndexHtmlService indexHtmlService,
            ImmutableMap<Pattern, Object> uriMappings, HttpSessionManager httpSessionManager,
            ImmutableList<Object> jsonServices) {

        ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true).build();
        ExecutorService bossExecutor = Executors.newCachedThreadPool(threadFactory);
        ExecutorService workerExecutor = Executors.newCachedThreadPool(threadFactory);
        PrefixingThreadNameDeterminer determiner = new PrefixingThreadNameDeterminer();
        bootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(
                new NioServerBossPool(bossExecutor, 1, determiner),
                new NioWorkerPool(workerExecutor, numWorkerThreads, determiner)));

        final HttpServerHandler handler = new HttpServerHandler(indexHtmlService, uriMappings,
                httpSessionManager, jsonServices);
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            public ChannelPipeline getPipeline() {
                ChannelPipeline pipeline = Channels.pipeline();
                pipeline.addLast("decoder", new HttpRequestDecoder());
                pipeline.addLast("aggregator", new HttpChunkAggregator(65536));
                pipeline.addLast("encoder", new HttpResponseEncoder());
                pipeline.addLast("deflater", new HttpContentCompressor());
                pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
                pipeline.addLast("handler", handler);
                return pipeline;
            }
        });
        this.handler = handler;
        InetSocketAddress localAddress = new InetSocketAddress(port);
        logger.debug("<init>(): binding http server to port {}", port);
        Channel serverChannel;
        try {
            serverChannel = bootstrap.bind(localAddress);
        } catch (ChannelException e) {
            serverChannel = bootstrap.bind(new InetSocketAddress(0));
            logger.error("unable to bind http listener to port {}, bound to port {} instead", port,
                    ((InetSocketAddress) serverChannel.getLocalAddress()).getPort());
        }
        this.serverChannel = serverChannel;
        this.port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        logger.debug("<init>(): http server bound");
    }

    int getPort() {
        return port;
    }

    void changePort(final int newPort) throws InterruptedException, ExecutionException {
        // need to call from separate thread, since netty throws exception if I/O thread (serving
        // http request) calls awaitUninterruptibly(), which is called by bind() below
        Channel previousServerChannel = this.serverChannel;
        ChangePort changePort = new ChangePort(newPort);
        Thread thread = new Thread(changePort);
        thread.setDaemon(true);
        thread.setName("Glowroot-Temporary-Thread");
        thread.start();
        thread.join();
        Throwable t = changePort.throwable;
        if (t != null) {
            logger.warn(t.getMessage(), t);
            throw new ExecutionException(t);
        } else {
            previousServerChannel.close();
            handler.closeAllButCurrent();
        }
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
        @Nullable
        private volatile Throwable throwable;

        ChangePort(int newPort) {
            this.newPort = newPort;
        }

        public void run() {
            try {
                InetSocketAddress localAddress = new InetSocketAddress(newPort);
                HttpServer.this.serverChannel = bootstrap.bind(localAddress);
                HttpServer.this.port = newPort;
            } catch (Throwable t) {
                this.throwable = t;
            }
        }
    }

    private static class PrefixingThreadNameDeterminer implements ThreadNameDeterminer {

        private final AtomicInteger workerCount = new AtomicInteger();

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
