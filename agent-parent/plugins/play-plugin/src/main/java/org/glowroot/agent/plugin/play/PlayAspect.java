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
package org.glowroot.agent.plugin.play;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext.Priority;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.config.BooleanProperty;
import org.glowroot.agent.plugin.api.weaving.BindClassMeta;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

public class PlayAspect {

    private static final Pattern routePattern = Pattern.compile("\\$[^<]+<([^>]+)>");

    private static final BooleanProperty useAltTransactionNaming =
            Agent.getConfigService("play").getBooleanProperty("useAltTransactionNaming");

    private static final ConcurrentMap<String, String> simplifiedRoutes =
            new ConcurrentHashMap<String, String>();

    @Shim("io.netty.handler.codec.http.HttpRequest")
    public interface NettyHttpRequest {

        @Shim("io.netty.handler.codec.http.HttpMethod getMethod()")
        NettyHttpMethod glowroot$getMethod();

        @Nullable
        String getUri();
    }

    @Shim("io.netty.handler.codec.http.HttpMethod")
    public interface NettyHttpMethod {

        @Nullable
        String name();
    }

    // "play.core.routing.TaggingInvoker" is for play 2.4.x and later
    // "play.core.Router$Routes$TaggingInvoker" is for play 2.3.x
    @Shim("play.core.routing.TaggingInvoker|play.core.Router$Routes$TaggingInvoker")
    public interface TaggingInvoker {

        @Shim("scala.collection.immutable.Map cachedHandlerTags()")
        @Nullable
        ScalaMap glowroot$cachedHandlerTags();
    }

    @Shim("scala.collection.immutable.Map")
    public interface ScalaMap {

        @Shim("scala.Option get(java.lang.Object)")
        @Nullable
        ScalaOption glowroot$get(Object key);
    }

    @Shim("scala.Option")
    public interface ScalaOption {

        boolean isDefined();

        Object get();
    }

    @Pointcut(className = "play.core.server.netty.PlayRequestHandler", methodName = "channelRead",
            methodParameterTypes = {"io.netty.channel.ChannelHandlerContext", "java.lang.Object"},
            timerName = "http request")
    public static class ChannelReadAdvice {

        private static final TimerName timerName = Agent.getTimerName(ChannelReadAdvice.class);

        @OnBefore
        public static @Nullable TraceEntry onBefore(OptionalThreadContext context,
                @SuppressWarnings("unused") @BindParameter @Nullable Object ctx,
                @BindParameter @Nullable Object req) {
            if (!(req instanceof NettyHttpRequest)) {
                return null;
            }
            NettyHttpRequest request = (NettyHttpRequest) req;
            NettyHttpMethod method = request.glowroot$getMethod();
            String uri = nullToEmpty(request.getUri());
            String methodName = null;
            if (method != null) {
                methodName = method.name();
            }
            String message;
            if (methodName == null) {
                message = uri;
            } else {
                message = methodName + " " + uri;
            }
            TraceEntry traceEntry =
                    context.startTransaction("HTTP", uri, MessageSupplier.from(message), timerName);
            context.setAsyncTransaction();
            return traceEntry;
        }

        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.end();
            }
        }
    }

    @Pointcut(className = "play.core.routing.TaggingInvoker|play.core.Router$Routes$TaggingInvoker",
            methodName = "call", methodParameterTypes = {"scala.Function0"})
    public static class HandlerInvokerAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context,
                @BindReceiver TaggingInvoker taggingInvoker) {
            ScalaMap tags = taggingInvoker.glowroot$cachedHandlerTags();
            if (tags == null) {
                return;
            }
            if (useAltTransactionNaming.value()) {
                ScalaOption controllerOption = tags.glowroot$get("ROUTE_CONTROLLER");
                ScalaOption methodOption = tags.glowroot$get("ROUTE_ACTION_METHOD");
                if (controllerOption != null && controllerOption.isDefined() && methodOption != null
                        && methodOption.isDefined()) {
                    String controller = controllerOption.get().toString();
                    String transactionName =
                            getAltTransactionName(controller, methodOption.get().toString());
                    context.setTransactionName(transactionName, Priority.CORE_PLUGIN);
                }
            } else {
                ScalaOption option = tags.glowroot$get("ROUTE_PATTERN");
                if (option != null && option.isDefined()) {
                    String route = option.get().toString();
                    route = simplifiedRoute(route);
                    context.setTransactionName(route, Priority.CORE_PLUGIN);
                }
            }
        }
    }

    @Pointcut(className = "views.html.*", methodName = "apply", methodParameterTypes = {".."},
            timerName = "play render", nestingGroup = "play-render")
    public static class RenderAdvice {

        private static final TimerName timerName = Agent.getTimerName(ChannelReadAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context, @BindReceiver Object view) {
            String viewName = view.getClass().getSimpleName();
            // strip off trailing $
            viewName = viewName.substring(0, viewName.length() - 1);
            return context.startTraceEntry(MessageSupplier.from("play render: {}", viewName),
                    timerName);
        }

        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable throwable,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(throwable);
        }
    }

    @Pointcut(className = "io.netty.channel.ChannelHandlerContext", methodName = "writeAndFlush",
            methodParameterTypes = {".."})
    public static class WriteAndFlushAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context) {
            context.completeAsyncTransaction();
        }
    }

    // visible for testing
    static String simplifiedRoute(String route) {
        String simplifiedRoute = simplifiedRoutes.get(route);
        if (simplifiedRoute == null) {
            Matcher matcher = routePattern.matcher(route);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String regex = nullToEmpty(matcher.group(1));
                regex = regex.replace("[^/]+", "*");
                regex = regex.replace(".+", "**");
                matcher.appendReplacement(sb, regex);
            }
            matcher.appendTail(sb);
            simplifiedRoute = sb.toString();
            simplifiedRoutes.putIfAbsent(route, simplifiedRoute);
        }
        return simplifiedRoute;
    }

    private static String getAltTransactionName(String controller, String methodName) {
        int index = controller.lastIndexOf('.');
        if (index != -1) {
            controller = controller.substring(index + 1);
        }
        return controller + "#" + methodName;
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    // ========== play 2.0.x - 2.4.x ==========

    @Shim("org.jboss.netty.handler.codec.http.HttpMethod")
    public interface OldNettyHttpMethod {

        @Nullable
        String getName();
    }

    @Shim("org.jboss.netty.handler.codec.http.HttpRequest")
    public interface OldNettyHttpRequest {

        @Shim("org.jboss.netty.handler.codec.http.HttpMethod getMethod()")
        OldNettyHttpMethod glowroot$getMethod();

        @Nullable
        String getUri();
    }

    @Shim("org.jboss.netty.channel.MessageEvent")
    public interface OldNettyMessageEvent {

        @Nullable
        Object getMessage();
    }

    @Pointcut(className = "play.core.server.netty.PlayDefaultUpstreamHandler",
            methodName = "messageReceived",
            methodParameterTypes = {"org.jboss.netty.channel.ChannelHandlerContext",
                    "org.jboss.netty.channel.MessageEvent"})
    public static class ChannelRead24xAdvice {

        private static final TimerName timerName = Agent.getTimerName(ChannelReadAdvice.class);

        @OnBefore
        public static @Nullable TraceEntry onBefore(OptionalThreadContext context,
                @SuppressWarnings("unused") @BindParameter @Nullable Object ctx,
                @BindParameter @Nullable OldNettyMessageEvent messageEvent) {
            if (messageEvent == null) {
                return null;
            }
            Object req = messageEvent.getMessage();
            if (!(req instanceof OldNettyHttpRequest)) {
                return null;
            }
            OldNettyHttpRequest request = (OldNettyHttpRequest) req;
            OldNettyHttpMethod method = request.glowroot$getMethod();
            String uri = nullToEmpty(request.getUri());
            String methodName = null;
            if (method != null) {
                methodName = method.getName();
            }
            String message;
            if (methodName == null) {
                message = uri;
            } else {
                message = methodName + " " + uri;
            }
            TraceEntry traceEntry =
                    context.startTransaction("HTTP", uri, MessageSupplier.from(message), timerName);
            context.setAsyncTransaction();
            return traceEntry;
        }

        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.end();
            }
        }
    }

    @Shim("org.jboss.netty.channel.MessageEvent")
    public interface MessageEvent {
        Object getMessage();
    }

    @Shim("com.typesafe.netty.http.pipelining.OrderedDownstreamChannelEvent")
    public interface OrderedDownstreamChannelEvent {
        boolean isLast();
    }

    @Shim("org.jboss.netty.handler.codec.http.HttpMessage")
    public interface HttpMessage {
        boolean isChunked();
    }

    @Shim("org.jboss.netty.handler.codec.http.HttpChunk")
    public interface HttpChunk {
        boolean isLast();
    }

    @Pointcut(className = "org.jboss.netty.channel.ChannelDownstreamHandler",
            methodName = "handleDownstream",
            methodParameterTypes = {"org.jboss.netty.channel.ChannelHandlerContext",
                    "org.jboss.netty.channel.ChannelEvent"})
    public static class HandleDownstreamAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context,
                @SuppressWarnings("unused") @BindParameter Object channelHandlerContext,
                @BindParameter Object channelEvent) {
            if (channelEvent instanceof OrderedDownstreamChannelEvent) {
                // play 2.2.x and later implements its own chunked transfer, not using netty's
                // MessageEvent/HttpMessage/HttpChunk
                if (((OrderedDownstreamChannelEvent) channelEvent).isLast()) {
                    context.completeAsyncTransaction();
                }
                return;
            }
            if (!(channelEvent instanceof MessageEvent)) {
                return;
            }
            Object messageEvent = ((MessageEvent) channelEvent).getMessage();
            if (messageEvent instanceof HttpMessage) {
                if (!((HttpMessage) messageEvent).isChunked()) {
                    context.completeAsyncTransaction();
                }
                return;
            }
            if (messageEvent instanceof HttpChunk) {
                if (((HttpChunk) messageEvent).isLast()) {
                    context.completeAsyncTransaction();
                }
                return;
            }
        }
    }

    // the field and method names are verbose to avoid conflict since they will become fields
    // and methods in all classes that extend org.jboss.netty.channel.ChannelFutureListener
    @Mixin({"org.jboss.netty.channel.ChannelFutureListener"})
    public abstract static class ListenerImpl implements ListenerMixin {

        private volatile @Nullable AuxThreadContext glowroot$auxContext;

        @Override
        public @Nullable AuxThreadContext glowroot$getAuxContext() {
            return glowroot$auxContext;
        }

        @Override
        public void glowroot$setAuxContext(@Nullable AuxThreadContext auxContext) {
            this.glowroot$auxContext = auxContext;
        }
    }

    // the method names are verbose to avoid conflict since they will become methods in all classes
    // that extend org.jboss.netty.channel.ChannelFutureListener
    public interface ListenerMixin {

        @Nullable
        AuxThreadContext glowroot$getAuxContext();

        void glowroot$setAuxContext(@Nullable AuxThreadContext auxContext);
    }

    @Pointcut(className = "org.jboss.netty.channel.DefaultChannelFuture",
            methodName = "addListener",
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
        @OnBefore
        public static @Nullable TraceEntry onBefore(@BindReceiver ListenerMixin listener) {
            AuxThreadContext auxContext = listener.glowroot$getAuxContext();
            if (auxContext != null) {
                listener.glowroot$setAuxContext(null);
                return auxContext.start();
            }
            return null;
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

    // ========== play 2.0.x - 2.2.x ==========

    @Shim("play.core.Router$HandlerDef")
    public interface HandlerDef {

        @Nullable
        String controller();

        @Nullable
        String method();
    }

    @Pointcut(className = "play.core.Router$HandlerInvoker", methodName = "call",
            methodParameterTypes = {"scala.Function0", "play.core.Router$HandlerDef"})
    public static class OldHandlerInvokerAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context,
                @SuppressWarnings("unused") @BindParameter Object action,
                @BindParameter HandlerDef handlerDef, @BindClassMeta PlayInvoker invoker) {
            String controller = handlerDef.controller();
            String method = handlerDef.method();
            // path() method doesn't exist in play 2.0.x so need to use reflection instead of shim
            String path = invoker.path(handlerDef);
            if (useAltTransactionNaming.value() || path == null) {
                if (controller != null && method != null) {
                    context.setTransactionName(getAltTransactionName(controller, method),
                            Priority.CORE_PLUGIN);
                }
            } else {
                path = simplifiedRoute(path);
                context.setTransactionName(path, Priority.CORE_PLUGIN);
            }
        }
    }

    // ========== play 1.x ==========

    @Pointcut(className = "play.mvc.ActionInvoker", methodName = "invoke",
            methodParameterTypes = {"play.mvc.Http$Request", "play.mvc.Http$Response"},
            timerName = "http request")
    public static class ActionInvokerAdvice {

        private static final TimerName timerName = Agent.getTimerName(ActionInvokerAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(OptionalThreadContext context) {
            // FIXME
            return context.startTransaction("Play", "TODO",
                    MessageSupplier.from("play action invoker"), timerName);
        }

        @OnReturn
        public static void onReturn(ThreadContext context, @BindTraveler TraceEntry traceEntry,
                @BindParameter Object request, @BindClassMeta PlayInvoker invoker) {
            String action = invoker.getAction(request);
            if (action != null) {
                int index = action.lastIndexOf('.');
                if (index != -1) {
                    action = action.substring(0, index) + '#' + action.substring(index + 1);
                }
                context.setTransactionName(action, Priority.CORE_PLUGIN);
            }
            traceEntry.end();
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable throwable,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(throwable);
        }
    }
}
