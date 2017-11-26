/*
 * Copyright 2016-2017 the original author or authors.
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

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

public class NettyAspect {

    // the field and method names are verbose since they will be mixed in to existing classes
    @Mixin({"io.netty.channel.Channel"})
    public abstract static class ChannelImpl implements ChannelMixin {

        private volatile boolean glowroot$completeAsyncTransaction;
        private volatile @Nullable AuxThreadContext glowroot$auxContext;

        @Override
        public boolean glowroot$getCompleteAsyncTransaction() {
            return glowroot$completeAsyncTransaction;
        }

        @Override
        public void glowroot$setCompleteAsyncTransaction(boolean completeAsyncTransaction) {
            glowroot$completeAsyncTransaction = completeAsyncTransaction;
        }

        @Override
        public @Nullable AuxThreadContext glowroot$getAuxContext() {
            return glowroot$auxContext;
        }

        @Override
        public void glowroot$setAuxContext(@Nullable AuxThreadContext auxContext) {
            glowroot$auxContext = auxContext;
        }
    }

    // the method names are verbose since they will be mixed in to existing classes
    public interface ChannelMixin {

        boolean glowroot$getCompleteAsyncTransaction();

        void glowroot$setCompleteAsyncTransaction(boolean completeAsyncTransaction);

        @Nullable
        AuxThreadContext glowroot$getAuxContext();

        void glowroot$setAuxContext(@Nullable AuxThreadContext auxThreadContext);
    }

    @Shim("io.netty.channel.ChannelHandlerContext")
    public interface ChannelHandlerContext {

        @Shim("io.netty.channel.Channel channel()")
        @Nullable
        ChannelMixin glowroot$channel();
    }

    @Shim("io.netty.handler.codec.http.HttpRequest")
    public interface HttpRequest {

        @Shim("io.netty.handler.codec.http.HttpMethod getMethod()")
        HttpMethod glowroot$getMethod();

        @Nullable
        String getUri();
    }

    @Shim("io.netty.handler.codec.http.HttpMethod")
    public interface HttpMethod {
        @Nullable
        String name();
    }

    @Shim("io.netty.handler.codec.http.LastHttpContent")
    public interface LastHttpContent {}

    @Pointcut(className = "io.netty.channel.ChannelHandlerContext", methodName = "fireChannelRead",
            methodParameterTypes = {"java.lang.Object"}, nestingGroup = "netty-inbound",
            timerName = "http request")
    public static class InboundAdvice {

        private static final TimerName timerName = Agent.getTimerName(InboundAdvice.class);

        @OnBefore
        public static @Nullable TraceEntry onBefore(OptionalThreadContext context,
                @BindReceiver ChannelHandlerContext channelHandlerContext,
                @BindParameter @Nullable Object msg) {
            ChannelMixin channel = channelHandlerContext.glowroot$channel();
            if (channel == null) {
                return null;
            }
            AuxThreadContext auxContext = channel.glowroot$getAuxContext();
            if (auxContext != null) {
                return auxContext.start();
            }
            if (!(msg instanceof HttpRequest)) {
                return null;
            }
            HttpRequest request = (HttpRequest) msg;
            HttpMethod method = request.glowroot$getMethod();
            String methodName = method == null ? null : method.name();
            TraceEntry traceEntry =
                    startAsyncTransaction(context, methodName, request.getUri(), timerName);
            channel.glowroot$setCompleteAsyncTransaction(true);
            if (!(msg instanceof LastHttpContent)) {
                channel.glowroot$setAuxContext(context.createAuxThreadContext());
            }
            return traceEntry;
        }

        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.end();
            }
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithError(t);
            }
        }
    }

    @Pointcut(className = "io.netty.channel.ChannelHandlerContext",
            methodName = "fireChannelReadComplete", methodParameterTypes = {},
            nestingGroup = "netty-inbound", timerName = "http request")
    public static class InboundCompleteAdvice {

        @OnBefore
        public static @Nullable TraceEntry onBefore(
                @BindReceiver ChannelHandlerContext channelHandlerContext) {
            ChannelMixin channel = channelHandlerContext.glowroot$channel();
            if (channel == null) {
                return null;
            }
            AuxThreadContext auxContext = channel.glowroot$getAuxContext();
            if (auxContext == null) {
                return null;
            }
            return auxContext.start();
        }

        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.end();
            }
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithError(t);
            }
        }
    }

    @Pointcut(className = "io.netty.channel.ChannelOutboundHandler", methodName = "write",
            methodParameterTypes = {"io.netty.channel.ChannelHandlerContext",
                    "java.lang.Object", "io.netty.channel.ChannelPromise"})
    public static class OutboundAdvice {

        @IsEnabled
        public static boolean isEnabled(
                @BindParameter @Nullable ChannelHandlerContext channelHandlerContext) {
            if (channelHandlerContext == null) {
                return false;
            }
            ChannelMixin channel = channelHandlerContext.glowroot$channel();
            return channel != null && channel.glowroot$getCompleteAsyncTransaction();
        }

        @OnAfter
        public static void onAfter(ThreadContext context,
                @BindParameter @Nullable ChannelHandlerContext channelHandlerContext,
                @BindParameter @Nullable Object msg) {
            if (channelHandlerContext == null) {
                return;
            }
            if (msg instanceof LastHttpContent) {
                completeAsyncTransaction(context, channelHandlerContext);
            }
        }

        private static void completeAsyncTransaction(ThreadContext context,
                ChannelHandlerContext channelHandlerContext) {
            context.setTransactionAsyncComplete();
            ChannelMixin channel = channelHandlerContext.glowroot$channel();
            if (channel != null) {
                channel.glowroot$setCompleteAsyncTransaction(false);
                channel.glowroot$setAuxContext(null);
            }
        }
    }

    // ChannelOutboundInvoker is interface of ChannelHandlerContext in early 4.x versions and
    // close() is defined on ChannelOutboundInvoker in those versions
    @Pointcut(className = "io.netty.channel.ChannelHandlerContext"
            + "|io.netty.channel.ChannelOutboundInvoker", methodName = "close",
            methodParameterTypes = {})
    public static class CloseAdvice {

        @OnBefore
        public static void onBefore(ThreadContext context) {
            context.setTransactionAsyncComplete();
        }
    }

    static TraceEntry startAsyncTransaction(OptionalThreadContext context,
            @Nullable String methodName, @Nullable String uri, TimerName timerName) {
        String path = getPath(uri);
        String message;
        if (methodName == null) {
            message = uri;
        } else {
            message = methodName + " " + uri;
        }
        TraceEntry traceEntry =
                context.startTransaction("Web", path, MessageSupplier.create(message), timerName);
        context.setTransactionAsync();
        return traceEntry;
    }

    private static String getPath(@Nullable String uri) {
        String path;
        if (uri == null) {
            path = "";
        } else {
            int index = uri.indexOf('?');
            if (index == -1) {
                path = uri;
            } else {
                path = uri.substring(0, index);
            }
        }
        return path;
    }
}
