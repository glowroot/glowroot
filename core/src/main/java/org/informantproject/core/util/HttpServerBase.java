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

import org.jboss.netty.bootstrap.ServerBootstrap;
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
import org.jboss.netty.util.ThreadNameDeterminer;
import org.jboss.netty.util.ThreadRenamingRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for a very simple Netty-based http server.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public abstract class HttpServerBase {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerBase.class);

    private final ServerBootstrap bootstrap;
    private final ChannelGroup allChannels;

    static {
        ThreadRenamingRunnable.setThreadNameDeterminer(new ThreadNameDeterminer() {
            public String determineThreadName(String currentThreadName, String proposedThreadName)
                    throws Exception {
                return "Informant-" + proposedThreadName;
            }
        });
    }

    public HttpServerBase(int port) {
        // thread names will be overridden by ThreadNameDeterminer above
        bootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(
                DaemonExecutors.newCachedThreadPool("<thread name will be overridden>"),
                DaemonExecutors.newCachedThreadPool("<thread name will be overridden>")));
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            public ChannelPipeline getPipeline() {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast("decoder", new HttpRequestDecoder());
                pipeline.addLast("aggregator", new HttpChunkAggregator(65536));
                pipeline.addLast("encoder", new HttpResponseEncoder());
                pipeline.addLast("deflater", new HttpContentCompressor());
                pipeline.addLast("handler", new SimpleHttpHandlerWrapper());
                return pipeline;
            }
        });
        allChannels = new DefaultChannelGroup();
        logger.debug("<init>(): binding http server to port {}", port);
        try {
            allChannels.add(bootstrap.bind(new InetSocketAddress(port)));
            logger.debug("<init>(): http server bound");
        } catch (ChannelException e) {
            logger.error("could not start informant http listener on port " + port, e);
            // allow everything else to proceed normally, but informant ui will not be available
        }
    }

    protected abstract HttpResponse handleRequest(HttpRequest request) throws IOException,
            InterruptedException;

    public void shutdown() {
        logger.debug("shutdown(): stopping http server");
        allChannels.close().awaitUninterruptibly();
        logger.debug("shutdown(): http server stopped");
        bootstrap.releaseExternalResources();
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
            HttpResponse response = handleRequest(request);
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
                if (e.getCause() instanceof IOException && e.getCause().getMessage()
                        .equals("An existing connection was forcibly closed by the remote host")) {
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
