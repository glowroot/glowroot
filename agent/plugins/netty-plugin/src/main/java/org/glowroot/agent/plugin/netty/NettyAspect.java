/*
 * Copyright 2016-2019 the original author or authors.
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

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;
import org.glowroot.agent.plugin.netty._.Util;

public class NettyAspect {

    // the field and method names are verbose since they will be mixed in to existing classes
    @Mixin({"io.netty.channel.Channel"})
    public abstract static class ChannelImpl implements ChannelMixin {

        private transient volatile @Nullable ThreadContext glowroot$threadContextToComplete;
        private transient volatile @Nullable AuxThreadContext glowroot$auxContext;

        @Override
        public @Nullable ThreadContext glowroot$getThreadContextToComplete() {
            return glowroot$threadContextToComplete;
        }

        @Override
        public void glowroot$setThreadContextToComplete(
                @Nullable ThreadContext threadContextToComplete) {
            glowroot$threadContextToComplete = threadContextToComplete;
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

        @Nullable
        ThreadContext glowroot$getThreadContextToComplete();

        void glowroot$setThreadContextToComplete(@Nullable ThreadContext completeAsyncTransaction);

        @Nullable
        AuxThreadContext glowroot$getAuxContext();

        void glowroot$setAuxContext(@Nullable AuxThreadContext auxThreadContext);
    }

    // need shims for netty-http-codec classes, since the pointcuts below are applied to
    // netty-transport classes, whether or not netty-codec-http is included on the classpath
    @Shim("io.netty.handler.codec.http.HttpRequest")
    public interface HttpRequestShim {

        @Shim("io.netty.handler.codec.http.HttpMethod getMethod()")
        HttpMethodShim glowroot$getMethod();

        @Nullable
        String getUri();
    }

    // need shims for netty-http-codec classes, since the pointcuts below are applied to
    // netty-transport classes, whether or not netty-codec-http is included on the classpath
    @Shim("io.netty.handler.codec.http.HttpMethod")
    public interface HttpMethodShim {
        @Nullable
        String name();
    }

    // need shims for netty-http-codec classes, since the pointcuts below are applied to
    // netty-transport classes, whether or not netty-codec-http is included on the classpath
    @Shim("io.netty.handler.codec.http.LastHttpContent")
    public interface LastHttpContentShim {}

    @Pointcut(className = "io.netty.channel.ChannelHandlerContext", methodName = "fireChannelRead",
            methodParameterTypes = {"java.lang.Object"}, nestingGroup = "netty-inbound",
            timerName = "http request")
    public static class InboundAdvice {

        private static final TimerName timerName = Agent.getTimerName(InboundAdvice.class);

        @OnBefore
        public static @Nullable TraceEntry onBefore(final OptionalThreadContext context,
                @BindReceiver ChannelHandlerContext channelHandlerContext,
                @BindParameter @Nullable Object msg) {
            Channel channel = channelHandlerContext.channel();
            if (channel == null) {
                return null;
            }
            final ChannelMixin channelMixin = (ChannelMixin) channel;
            AuxThreadContext auxContext = channelMixin.glowroot$getAuxContext();
            if (auxContext != null) {
                return auxContext.start();
            }
            if (!(msg instanceof HttpRequestShim)) {
                return null;
            }
            HttpRequestShim request = (HttpRequestShim) msg;
            HttpMethodShim method = request.glowroot$getMethod();
            String methodName = method == null ? null : method.name();
            TraceEntry traceEntry =
                    Util.startAsyncTransaction(context, methodName, request.getUri(), timerName);
            channelMixin.glowroot$setThreadContextToComplete(context);
            // IMPORTANT the close future gets called if client disconnects, but does not get called
            // when transaction ends and Keep-Alive is used (so still need to capture write
            // LastHttpContent below)
            channel.closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
                @Override
                public void operationComplete(Future<? super Void> future) {
                    endTransaction(channelMixin);
                }
            });
            if (!(msg instanceof LastHttpContentShim)) {
                channelMixin.glowroot$setAuxContext(context.createAuxThreadContext());
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
            nestingGroup = "netty-inbound")
    public static class InboundCompleteAdvice {

        @OnBefore
        public static @Nullable TraceEntry onBefore(
                @BindReceiver ChannelHandlerContext channelHandlerContext) {
            ChannelMixin channel = (ChannelMixin) channelHandlerContext.channel();
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

    // IMPORTANT the close future gets called if client disconnects, but does not get called when
    // transaction ends and Keep-Alive is used (so still need to capture write LastHttpContent
    // below)
    @Pointcut(className = "io.netty.channel.ChannelOutboundHandler", methodName = "write",
            methodParameterTypes = {"io.netty.channel.ChannelHandlerContext",
                    "java.lang.Object", "io.netty.channel.ChannelPromise"})
    public static class OutboundAdvice {

        @OnAfter
        public static void onAfter(
                @BindParameter @Nullable ChannelHandlerContext channelHandlerContext,
                @BindParameter @Nullable Object msg) {
            if (!(msg instanceof LastHttpContentShim)) {
                return;
            }
            if (channelHandlerContext == null) {
                return;
            }
            Channel channel = channelHandlerContext.channel();
            if (channel == null) {
                return;
            }
            endTransaction((ChannelMixin) channel);
        }
    }

    private static void endTransaction(ChannelMixin channelMixin) {
        ThreadContext context = channelMixin.glowroot$getThreadContextToComplete();
        if (context != null) {
            context.setTransactionAsyncComplete();
            channelMixin.glowroot$setThreadContextToComplete(null);
            channelMixin.glowroot$setAuxContext(null);
        }
    }
}
