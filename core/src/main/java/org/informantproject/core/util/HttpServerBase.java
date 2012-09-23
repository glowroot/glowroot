/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.core.util;

import static org.jboss.netty.channel.Channels.pipeline;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelException;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;

/**
 * Base class for a very simple Netty-based http server.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public abstract class HttpServerBase {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerBase.class);

    private static final ImmutableSet<String> BROWSER_DISCONNECT_MESSAGES = ImmutableSet.of(
            "An existing connection was forcibly closed by the remote host",
            "An established connection was aborted by the software in your host machine");

    private final ServerBootstrap bootstrap;
    private final ChannelGroup allChannels;

    private final int port;

    public HttpServerBase(int port) {
        // thread names will be overridden by ThreadNameDeterminer above
        bootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(
                DaemonExecutors.newCachedThreadPool("Informant-HttpServer-Boss"),
                DaemonExecutors.newCachedThreadPool("Informant-HttpServer-Executor")));
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            public ChannelPipeline getPipeline() {
                ChannelPipeline pipeline = pipeline();
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

    public int getPort() {
        return port;
    }

    public void close() {
        logger.debug("close(): stopping http server");
        allChannels.close().awaitUninterruptibly();
        logger.debug("close(): http server stopped");
        bootstrap.releaseExternalResources();
    }

    @Nullable
    protected abstract HttpResponse handleRequest(HttpRequest request, Channel channel)
            throws IOException, InterruptedException;

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
                    logger.error(e.getCause().getMessage(), e.getCause());
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
