/*
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
package org.glowroot.agent.plugin.netty;

import java.io.File;

import com.google.common.io.Files;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class HttpServerHandler extends ChannelInboundHandlerAdapter {

    private static final byte[] CONTENT = {'H', 'e', 'l', 'l', 'o', ' ', 'W', 'o', 'r', 'l', 'd'};

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;
            @SuppressWarnings("deprecation")
            String uri = request.getUri();
            if (uri.equals("/exception")) {
                throw new Exception("Test");
            }
            if (uri.equals("/chunked")) {
                HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
                response.headers().set("transfer-encoding", "chunked");
                response.headers().set("content-type", "text/plain");
                ctx.write(response);
                final File file = File.createTempFile("glowroot-netty-plugin-it-", ".txt");
                final ChunkedFile chunkedFile = new ChunkedFile(file);
                Files.write(CONTENT, file);
                ctx.write(chunkedFile)
                        .addListener(ChannelFutureListener.CLOSE)
                        .addListener(new GenericFutureListener<Future<Void>>() {
                            @Override
                            public void operationComplete(Future<Void> arg0) throws Exception {
                                chunkedFile.close();
                                if (!file.delete()) {
                                    throw new IllegalStateException(
                                            "Could not delete file: " + file.getPath());
                                }
                            }
                        });
                return;
            }
            FullHttpResponse response =
                    new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer(CONTENT));
            response.headers().set("Content-Type", "text/plain");
            response.headers().set("Content-Length", response.content().readableBytes());
            ctx.write(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
