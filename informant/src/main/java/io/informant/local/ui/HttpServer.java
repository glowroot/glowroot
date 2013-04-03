/**
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
package io.informant.local.ui;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpContentCompressor;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.jboss.netty.util.ThreadNameDeterminer;
import org.jboss.netty.util.ThreadRenamingRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.local.ui.HttpServerHandler.JsonServiceMapping;
import io.informant.markers.OnlyUsedByTests;
import io.informant.markers.Singleton;

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
    private final Channel serverChannel;
    private final HttpServerHandler handler;

    private final int port;

    HttpServer(int port, int numWorkerThreads, ImmutableMap<Pattern, Object> uriMappings,
            ImmutableList<JsonServiceMapping> jsonServiceMappings) {
        setThreadNameDeterminer();
        handler = new HttpServerHandler(uriMappings, jsonServiceMappings);
        ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true).build();
        ExecutorService bossExecutor = Executors.newCachedThreadPool(threadFactory);
        ExecutorService workerExecutor = Executors.newCachedThreadPool(threadFactory);
        bootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(bossExecutor, 1,
                workerExecutor, numWorkerThreads));
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
        InetSocketAddress localAddress = new InetSocketAddress(port);
        logger.debug("<init>(): binding http server to port {}", port);
        serverChannel = bootstrap.bind(localAddress);
        this.port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        logger.debug("<init>(): http server bound");
    }

    void close() {
        logger.debug("close(): stopping http server");
        serverChannel.close().awaitUninterruptibly();
        handler.close();
        logger.debug("close(): http server stopped");
        bootstrap.releaseExternalResources();
    }

    @OnlyUsedByTests
    public int getPort() {
        return port;
    }

    private static void setThreadNameDeterminer() {
        ThreadRenamingRunnable.setThreadNameDeterminer(new ThreadNameDeterminer() {
            private final AtomicInteger workerCount = new AtomicInteger();
            public String determineThreadName(String currentThreadName, String proposedThreadName) {
                if (proposedThreadName.matches("New I/O server boss #[0-9]+")) {
                    // leave off the # since there is always a single boss thread
                    return "Informant-Http-Boss";
                }
                if (proposedThreadName.matches("New I/O worker #[0-9]+")) {
                    // use separate worker specific counter since netty # is shared between bosses
                    // and workers (and netty # starts at 1 while other informant thread pools start
                    // numbering at 0)
                    return "Informant-Http-Worker-" + workerCount.getAndIncrement();
                }
                logger.warn("unexpected thread name: '{}'", proposedThreadName);
                return proposedThreadName;
            }
        });
    }
}
