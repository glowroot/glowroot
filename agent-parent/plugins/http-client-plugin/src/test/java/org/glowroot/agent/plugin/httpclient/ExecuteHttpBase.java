/**
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.plugin.httpclient;

import java.net.InetSocketAddress;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.TransactionMarker;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public abstract class ExecuteHttpBase implements AppUnderTest, TransactionMarker {

    private int port;

    @Override
    public void executeApp() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(2);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(1048576));
                        p.addLast(new SimpleHttpHandler());
                    }
                });
        Channel channel = bootstrap.bind(0).sync().channel();
        port = ((InetSocketAddress) channel.localAddress()).getPort();
        try {
            transactionMarker();
        } finally {
            channel.close().awaitUninterruptibly();
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    protected int getPort() {
        return port;
    }

    static class SimpleHttpHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            FullHttpRequest request = (FullHttpRequest) msg;
            FullHttpResponse response =
                    new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.OK);
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ChannelFuture f = ctx.write(response);
            f.addListener(ChannelFutureListener.CLOSE);
            request.release();
        }
    }
}
