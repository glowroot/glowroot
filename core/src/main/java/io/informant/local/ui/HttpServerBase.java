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

import io.informant.marker.ThreadSafe;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelException;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpContentCompressor;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpHeaders.Names;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.jboss.netty.util.ThreadNameDeterminer;
import org.jboss.netty.util.ThreadRenamingRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.nullness.quals.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Base class for a very simple Netty-based http server.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
abstract class HttpServerBase {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerBase.class);

    private static final ImmutableSet<String> BROWSER_DISCONNECT_MESSAGES = ImmutableSet.of(
            "An existing connection was forcibly closed by the remote host",
            "An established connection was aborted by the software in your host machine");

    private final ServerBootstrap bootstrap;
    private final ChannelGroup allChannels;

    private final int port;

    HttpServerBase(int port, int numWorkerThreads) {
        setThreadNameDeterminer();
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
                pipeline.addLast("handler", new SimpleHttpHandlerWrapper());
                return pipeline;
            }
        });
        allChannels = new DefaultChannelGroup();
        InetSocketAddress localAddress = new InetSocketAddress(port);
        Channel channel;
        try {
            logger.debug("<init>(): binding http server to port {}", port);
            channel = bootstrap.bind(localAddress);
        } catch (ChannelException e) {
            logger.error("could not start informant http listener on port {}", port);
            this.port = -1;
            // don't rethrow, allow everything else to proceed normally, but informant ui will not
            // be available
            return;
        }
        this.port = ((InetSocketAddress) channel.getLocalAddress()).getPort();
        allChannels.add(channel);
        logger.debug("<init>(): http server bound");
    }

    @VisibleForTesting
    public int getPort() {
        return port;
    }

    void close() {
        logger.debug("close(): stopping http server");
        allChannels.close().awaitUninterruptibly();
        logger.debug("close(): http server stopped");
        bootstrap.releaseExternalResources();
    }

    @Nullable
    protected abstract HttpResponse handleRequest(HttpRequest request, Channel channel)
            throws IOException, InterruptedException;

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

    private class SimpleHttpHandlerWrapper extends SimpleChannelUpstreamHandler {
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
    }
}
