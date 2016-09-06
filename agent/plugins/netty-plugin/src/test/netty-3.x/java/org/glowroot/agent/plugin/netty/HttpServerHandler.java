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

import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.util.CharsetUtil;

public class HttpServerHandler extends SimpleChannelUpstreamHandler {

    private HttpRequest request;
    private boolean readingChunks;

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        if (!readingChunks) {
            request = (HttpRequest) e.getMessage();
            String uri = request.getUri();
            if (uri.equals("/exception")) {
                throw new Exception("Test");
            }
            if (request.isChunked()) {
                readingChunks = true;
            } else {
                writeResponse(e);
            }
        } else {
            HttpChunk chunk = (HttpChunk) e.getMessage();
            if (chunk.isLast()) {
                readingChunks = false;
                writeResponse(e);
            }
        }
    }

    private void writeResponse(MessageEvent e) throws Exception {
        HttpResponse response =
                new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.setContent(ChannelBuffers.copiedBuffer("Hello world", CharsetUtil.UTF_8));
        addHeader(response, HttpHeaders.Names.CONTENT_TYPE, "text/plain; charset=UTF-8");
        ChannelFuture future = e.getChannel().write(response);
        future.addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        e.getChannel().close();
    }

    private static void addHeader(HttpResponse response, String name, String value)
            throws Exception {
        try {
            Object headers = HttpResponse.class.getMethod("headers").invoke(response);
            Class<?> httpHeadersClass =
                    Class.forName("org.jboss.netty.handler.codec.http.HttpHeaders");
            httpHeadersClass.getMethod("add", String.class, Object.class).invoke(headers, name,
                    value);
        } catch (Exception e) {
            // netty prior to 3.8.0
            HttpResponse.class.getMethod("addHeader", String.class, Object.class).invoke(response,
                    name, value);
        }
    }
}
