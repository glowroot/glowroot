/*
 * Copyright 2016-2023 the original author or authors.
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

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.checker.NonNull;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;
import org.glowroot.agent.plugin.netty.bclglowrootbcl.Util;

public class Netty3Aspect {

    // the field and method names are verbose since they will be mixed in to existing classes
    @Mixin({"org.jboss.netty.channel.Channel"})
    public abstract static class ChannelImpl implements ChannelMixin {

        private transient volatile boolean glowroot$completeAsyncTransaction;

        @Override
        public boolean glowroot$getCompleteAsyncTransaction() {
            return glowroot$completeAsyncTransaction;
        }

        @Override
        public void glowroot$setCompleteAsyncTransaction(boolean completeAsyncTransaction) {
            glowroot$completeAsyncTransaction = completeAsyncTransaction;
        }
    }

    // the method names are verbose since they will be mixed in to existing classes
    public interface ChannelMixin {

        boolean glowroot$getCompleteAsyncTransaction();

        void glowroot$setCompleteAsyncTransaction(boolean completeAsyncTransaction);
    }

    // the field and method names are verbose since they will be mixed in to existing classes
    @Mixin({"org.jboss.netty.channel.ChannelFutureListener"})
    public abstract static class ListenerImpl implements ListenerMixin {

        private transient volatile @Nullable AuxThreadContext glowroot$auxContext;

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
    public interface ListenerMixin {

        @Nullable
        AuxThreadContext glowroot$getAuxContext();

        void glowroot$setAuxContext(@Nullable AuxThreadContext auxContext);
    }

    @Shim("org.jboss.netty.channel.ChannelHandlerContext")
    public interface ChannelHandlerContextShim {

        @Shim("org.jboss.netty.channel.Channel getChannel()")
        @Nullable
        ChannelMixin glowroot$getChannel();
    }

    @Shim("org.jboss.netty.handler.codec.http.HttpRequest")
    public interface HttpRequestShim {

        @Shim("org.jboss.netty.handler.codec.http.HttpMethod getMethod()")
        HttpMethodShim glowroot$getMethod();

        @Nullable
        String getUri();
    }

    @Shim("org.jboss.netty.handler.codec.http.HttpMethod")
    public interface HttpMethodShim {
        @Nullable
        String getName();
    }

    @Shim("org.jboss.netty.channel.MessageEvent")
    public interface MessageEventShim {
        @Nullable
        Object getMessage();
    }

    @Shim("org.jboss.netty.handler.codec.http.HttpMessage")
    public interface HttpMessageShim {
        boolean isChunked();
    }

    @Shim("org.jboss.netty.handler.codec.http.HttpChunk")
    public interface HttpChunkShim {
        boolean isLast();
    }

    @Pointcut(className = "org.jboss.netty.channel.ChannelHandlerContext",
            methodName = "sendUpstream",
            methodParameterTypes = {"org.jboss.netty.channel.ChannelEvent"},
            nestingGroup = "netty-inbound", timerName = "http request")
    public static class InboundAdvice {

        private static final TimerName timerName = Agent.getTimerName(InboundAdvice.class);

        @IsEnabled
        public static boolean isEnabled(
                @BindReceiver ChannelHandlerContextShim channelHandlerContext,
                @BindParameter @Nullable Object channelEvent) {
            return channelHandlerContext.glowroot$getChannel() != null && channelEvent != null
                    && channelEvent instanceof MessageEventShim
                    && ((MessageEventShim) channelEvent).getMessage() instanceof HttpRequestShim;
        }

        @OnBefore
        public static TraceEntry onBefore(OptionalThreadContext context,
                @BindReceiver ChannelHandlerContextShim channelHandlerContext,
                // not null, just checked above in isEnabled()
                @BindParameter Object channelEvent) {
            @SuppressWarnings("nullness") // just checked above in isEnabled()
            @NonNull
            ChannelMixin channel = channelHandlerContext.glowroot$getChannel();
            // just checked valid cast above in isEnabled()
            @SuppressWarnings("nullness") // just checked above in isEnabled()
            @NonNull
            Object msg = ((MessageEventShim) channelEvent).getMessage();
            // just checked valid cast above in isEnabled()
            HttpRequestShim request = (HttpRequestShim) msg;
            HttpMethodShim method = request.glowroot$getMethod();
            String methodName = method == null ? null : method.getName();
            channel.glowroot$setCompleteAsyncTransaction(true);
            return Util.startAsyncTransaction(context, methodName, request.getUri(), timerName);
        }

        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(t);
        }
    }

    @Shim("com.typesafe.netty.http.pipelining.OrderedDownstreamChannelEvent")
    public interface OrderedDownstreamChannelEventShim {
        boolean isLast();
    }

    @Pointcut(className = "org.jboss.netty.channel.ChannelDownstreamHandler",
            methodName = "handleDownstream",
            methodParameterTypes = {"org.jboss.netty.channel.ChannelHandlerContext",
                    "org.jboss.netty.channel.ChannelEvent"})
    public static class OutboundAdvice {

        @IsEnabled
        public static boolean isEnabled(
                @BindParameter @Nullable ChannelHandlerContextShim channelHandlerContext) {
            if (channelHandlerContext == null) {
                return false;
            }
            ChannelMixin channel = channelHandlerContext.glowroot$getChannel();
            return channel != null && channel.glowroot$getCompleteAsyncTransaction();
        }

        @OnBefore
        public static void onBefore(ThreadContext context,
                @BindParameter @Nullable ChannelHandlerContextShim channelHandlerContext,
                @BindParameter @Nullable Object channelEvent) {
            if (channelHandlerContext == null) {
                return;
            }
            if (channelEvent instanceof OrderedDownstreamChannelEventShim) {
                // play 2.2.x and later implements its own chunked transfer, not using netty's
                // MessageEvent/HttpMessage/HttpChunk
                if (((OrderedDownstreamChannelEventShim) channelEvent).isLast()) {
                    completeAsyncTransaction(context, channelHandlerContext);
                }
                return;
            }
            if (!(channelEvent instanceof MessageEventShim)) {
                return;
            }
            Object messageEvent = ((MessageEventShim) channelEvent).getMessage();
            if (messageEvent instanceof HttpMessageShim) {
                if (!((HttpMessageShim) messageEvent).isChunked()) {
                    completeAsyncTransaction(context, channelHandlerContext);
                }
                return;
            }
            if (messageEvent instanceof HttpChunkShim) {
                if (((HttpChunkShim) messageEvent).isLast()) {
                    completeAsyncTransaction(context, channelHandlerContext);
                }
                return;
            }
        }

        private static void completeAsyncTransaction(ThreadContext context,
                ChannelHandlerContextShim channelHandlerContext) {
            context.setTransactionAsyncComplete();
            ChannelMixin channel = channelHandlerContext.glowroot$getChannel();
            if (channel != null) {
                channel.glowroot$setCompleteAsyncTransaction(false);
            }
        }
    }

    @Pointcut(className = "org.jboss.netty.channel.ChannelFuture", methodName = "addListener",
            methodParameterTypes = {"org.jboss.netty.channel.ChannelFutureListener"})
    public static class AddListenerAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context, @BindParameter ListenerMixin listener) {
            AuxThreadContext auxContext = context.createAuxThreadContext();
            listener.glowroot$setAuxContext(auxContext);
        }
    }

    @Pointcut(className = "org.jboss.netty.channel.ChannelFutureListener",
            methodName = "operationComplete",
            methodParameterTypes = {"org.jboss.netty.channel.ChannelFuture"})
    public static class OperationCompleteAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver ListenerMixin listener) {
            return listener.glowroot$getAuxContext() != null;
        }
        @OnBefore
        public static TraceEntry onBefore(@BindReceiver ListenerMixin listener) {
            @SuppressWarnings("nullness") // just checked above in isEnabled()
            @NonNull
            AuxThreadContext auxContext = listener.glowroot$getAuxContext();
            listener.glowroot$setAuxContext(null);
            return auxContext.start();
        }
        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(t);
        }
    }

    @Pointcut(className = "org.jboss.netty.channel.Channel", methodName = "close",
            methodParameterTypes = {})
    public static class CloseAdvice {

        @OnBefore
        public static void onBefore(ThreadContext context) {
            context.setTransactionAsyncComplete();
        }
    }
}
