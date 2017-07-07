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
package org.glowroot.agent.plugin.play;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
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
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

public class Play2xAspect {

    private static final Pattern routePattern = Pattern.compile("\\$[^<]+<([^>]+)>");

    private static final BooleanProperty useAltTransactionNaming =
            Agent.getConfigService("play").getBooleanProperty("useAltTransactionNaming");

    private static final ConcurrentMap<String, String> simplifiedRoutes =
            new ConcurrentHashMap<String, String>();

    // "play.core.routing.TaggingInvoker" is for play 2.4.x and later
    // "play.core.Router$Routes$TaggingInvoker" is for play 2.3.x
    @Shim({"play.core.routing.TaggingInvoker", "play.core.Router$Routes$TaggingInvoker"})
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

        private static final TimerName timerName = Agent.getTimerName(RenderAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context, @BindReceiver Object view) {
            String viewName = view.getClass().getSimpleName();
            // strip off trailing $
            viewName = viewName.substring(0, viewName.length() - 1);
            return context.startTraceEntry(MessageSupplier.create("play render: {}", viewName),
                    timerName);
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

    // visible for testing
    static String simplifiedRoute(String route) {
        String simplifiedRoute = simplifiedRoutes.get(route);
        if (simplifiedRoute == null) {
            Matcher matcher = routePattern.matcher(route);
            StringBuilder sb = new StringBuilder();
            int end = 0;
            while (matcher.find()) {
                if (end == 0) {
                    sb.append(route.substring(0, matcher.start()));
                }
                String regex = nullToEmpty(matcher.group(1));
                regex = regex.replace("[^/]+", "*");
                regex = regex.replace(".+", "**");
                sb.append(regex);
                end = matcher.end();
            }
            sb.append(route.substring(end));
            simplifiedRoute = sb.toString();
            simplifiedRoutes.putIfAbsent(route, simplifiedRoute);
        }
        return simplifiedRoute;
    }

    private static String getAltTransactionName(String controller, String methodName) {
        int index = controller.lastIndexOf('.');
        if (index == -1) {
            return controller + "#" + methodName;
        } else {
            return controller.substring(index + 1) + "#" + methodName;
        }
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
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
}
