/*
 * Copyright 2011-2016 the original author or authors.
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

import org.glowroot.agent.plugin.api.*;
import org.glowroot.agent.plugin.api.weaving.*;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.List;

public class NettyChannelAndCodecAspect {

    @Shim("io.netty.handler.codec.http.HttpMethod")
    public interface HttpMethod {
        @Nullable
        String name();
    }


    @Shim("io.netty.handler.codec.http.HttpRequest")
    public interface HttpRequest {
        @Shim("io.netty.handler.codec.http.HttpMethod getMethod()")
        @Nullable
        HttpMethod getMethod();

        @Nullable
        String getUri();
    }

    @Pointcut(className = "io.netty.handler.codec.http.HttpObjectDecoder", methodName = "decode",
            methodParameterTypes = {"io.netty.channel.ChannelHandlerContext", "io.netty.buffer.ByteBuf", "java.util.List"},
            timerName = "http decoder")
    public static class HttpObjectDecoderAdvice {
        private static final TimerName timerName = Agent.getTimerName(HttpObjectDecoderAdvice.class);

        @OnBefore
        public static
        @Nullable
        TraceEntry onBefore(OptionalThreadContext context, @BindParameter @Nullable Object ctx, @BindParameter @Nullable Object buffer, @BindParameter @Nullable List<Object> out) {
            return context.startTransaction("Servlet", "Test", MessageSupplier.from("test"), timerName);
        }

        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry == null) {
                return;
            }
            traceEntry.end();
        }

        @OnAfter
        public static void onAfter(OptionalThreadContext context, @BindParameter @Nullable Object ctx, @BindParameter @Nullable Object buffer, @BindParameter @Nullable List<Object> out) {
            Iterator i = out.iterator();
            while (i.hasNext()) {
                Object msg = i.next();
                if (msg instanceof HttpRequest) {
                    HttpRequest r = (HttpRequest) msg;
                    String transactionName = r.getMethod().name() + " " + r.getUri();
                    System.out.println("Changing transaction name to " + transactionName);
                    context.setTransactionName(transactionName, ThreadContext.Priority.CORE_PLUGIN);
                }
            }
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                                   @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry == null) {
                return;
            }

        }
    }

    @Pointcut(className = "io.netty.channel.ChannelInboundHandler", methodName = "channelRead",
            methodParameterTypes = {"io.netty.channel.ChannelHandlerContext", "java.lang.Object"},
            nestingGroup = "netty", timerName = "netty channel read")
    public static class ChannelInboundHandlerAdvice {
        private static final TimerName timerName = Agent.getTimerName(ChannelInboundHandlerAdvice.class);

        @OnBefore
        public static
        @Nullable
        TraceEntry onBefore(OptionalThreadContext context, @BindReceiver Object channel, @BindParameter @Nullable Object ctx, @BindParameter @Nullable Object message) {
            return context.startTransaction("Servlet", "Test", MessageSupplier.from("test"), timerName);
        }

        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry == null) {
                return;
            }
            traceEntry.end();
        }

    }

    @Pointcut(className = "io.netty.channel.ChannelOutboundHandler", methodName = "write",
            methodParameterTypes = {"io.netty.channel.ChannelHandlerContext", "java.lang.Object", "io.netty.channel.ChannelPromise"},
            timerName = "netty write")
    public static class ChannelOutboundHandlerAdvice {
        private static final TimerName timerName = Agent.getTimerName(ChannelOutboundHandlerAdvice.class);

        @OnBefore
        public static
        @Nullable
        TraceEntry onBefore(OptionalThreadContext context, @BindReceiver Object channel, @BindParameter @Nullable Object ctx, @BindParameter @Nullable Object message, @BindParameter @Nullable Object promise) {
            return context.startTransaction("Servlet", "Test", MessageSupplier.from("test"), timerName);
        }

        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry == null) {
                return;
            }
            traceEntry.end();
        }

    }


}