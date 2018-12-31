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

    private final EventLoopGroup group;
    private final Channel channel;

    Http2Server(int port, boolean supportHttp1) throws InterruptedException {
        group = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.option(ChannelOption.SO_BACKLOG, 1024);
        b.group(group)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(supportHttp1 ? new Http2ServerWithHttp1SupportInitializer()
                        : new Http2ServerInitializer());
        channel = b.bind(port).sync().channel();
    }

    void close() throws InterruptedException {
        channel.close();
        group.shutdownGracefully();
    }

    private class Http2ServerInitializer extends ChannelInitializer<SocketChannel> {

        @Override
        public void initChannel(SocketChannel ch) {
            ChannelPipeline p = ch.pipeline();
            p.addLast(Http2HandlerBuilder.create());
        }
    }

    private class Http2ServerWithHttp1SupportInitializer extends ChannelInitializer<SocketChannel> {

        @Override
        public void initChannel(SocketChannel ch) {
            ChannelPipeline p = ch.pipeline();
            HttpServerCodec codec = new HttpServerCodec();
            UpgradeCodecFactory upgradeCodecFactory = new UpgradeCodecFactory() {
                @Override
                public UpgradeCodec newUpgradeCodec(CharSequence protocol) {
                    if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME,
                            protocol)) {
                        return new Http2ServerUpgradeCodec(Http2HandlerBuilder.create());
                    } else {
                        return null;
                    }
                }
            };
            HttpServerUpgradeHandler upgradeHandler =
                    new HttpServerUpgradeHandler(codec, upgradeCodecFactory);
            p.addLast(new CleartextHttp2ServerUpgradeHandler(codec, upgradeHandler,
                    Http2HandlerBuilder.create()));
            p.addLast(new SimpleChannelInboundHandler<HttpMessage>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, HttpMessage msg)
                        throws Exception {
                    ChannelPipeline pipeline = ctx.pipeline();
                    ChannelHandlerContext thisCtx = pipeline.context(this);
                    pipeline.addAfter(thisCtx.name(), null, new Http1ServerHandler());
                    pipeline.replace(this, null, new HttpObjectAggregator(16 * 1024));
                    ctx.fireChannelRead(ReferenceCountUtil.retain(msg));
                }
            });
        }
    }

    private static class Http2HandlerBuilder
            extends AbstractHttp2ConnectionHandlerBuilder<Http2ServerHandler, Http2HandlerBuilder> {

        private static Http2ServerHandler create() {
            return new Http2HandlerBuilder().build();
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
