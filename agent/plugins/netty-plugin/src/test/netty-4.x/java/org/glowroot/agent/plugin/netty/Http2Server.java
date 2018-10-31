/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.agent.plugin.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeCodecFactory;
import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;

class Http2Server {

    private static final UpgradeCodecFactory upgradeCodecFactory = new UpgradeCodecFactory() {
        @Override
        public UpgradeCodec newUpgradeCodec(CharSequence protocol) {
            if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
                return new Http2ServerUpgradeCodec(new Http2HandlerBuilder().build());
            } else {
                return null;
            }
        }
    };

    private final EventLoopGroup group;
    private final Channel channel;

    Http2Server(int port) throws InterruptedException {
        group = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.option(ChannelOption.SO_BACKLOG, 1024);
        b.group(group)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new Http2ServerInitializer());
        channel = b.bind(port).sync().channel();
    }

    void close() throws InterruptedException {
        channel.close();
        group.shutdownGracefully();
    }

    private static class Http2ServerInitializer extends ChannelInitializer<SocketChannel> {

        @Override
        public void initChannel(SocketChannel ch) {
            final ChannelPipeline p = ch.pipeline();
            final HttpServerCodec sourceCodec = new HttpServerCodec();
            final HttpServerUpgradeHandler upgradeHandler =
                    new HttpServerUpgradeHandler(sourceCodec, upgradeCodecFactory);
            final CleartextHttp2ServerUpgradeHandler cleartextHttp2ServerUpgradeHandler =
                    new CleartextHttp2ServerUpgradeHandler(sourceCodec, upgradeHandler,
                            new Http2HandlerBuilder().build());

            p.addLast(cleartextHttp2ServerUpgradeHandler);
            p.addLast(new SimpleChannelInboundHandler<HttpMessage>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, HttpMessage msg)
                        throws Exception {
                    // If this handler is hit then no upgrade has been attempted and the client is
                    // just talking HTTP.
                    System.err.println("Directly talking: " + msg.protocolVersion()
                            + " (no upgrade was attempted)");
                    ChannelPipeline pipeline = ctx.pipeline();
                    ChannelHandlerContext thisCtx = pipeline.context(this);
                    pipeline.addAfter(thisCtx.name(), null, new HttpServerHandler());
                    pipeline.replace(this, null, new HttpObjectAggregator(16 * 1024));
                    ctx.fireChannelRead(ReferenceCountUtil.retain(msg));
                }
            });
        }
    }

    private static class Http2HandlerBuilder
            extends AbstractHttp2ConnectionHandlerBuilder<Http2ServerHandler, Http2HandlerBuilder> {

        @Override
        public Http2ServerHandler build() {
            return super.build();
        }

        @Override
        protected Http2ServerHandler build(Http2ConnectionDecoder decoder,
                Http2ConnectionEncoder encoder, Http2Settings initialSettings) {
            Http2ServerHandler handler = new Http2ServerHandler(decoder, encoder, initialSettings);
            frameListener(handler);
            return handler;
        }
    }
}
