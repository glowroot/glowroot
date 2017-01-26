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
package org.glowroot.agent.plugin.struts;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext.Priority;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

public class ActionProxyAspect {

    @Shim("com.opensymphony.xwork2.ActionProxy")
    public interface ActionProxy {

        Object getAction();

        String getMethod();
    }

    @Pointcut(className = "com.opensymphony.xwork2.ActionProxy", methodName = "execute",
            methodParameterTypes = {}, nestingGroup = "struts", timerName = "struts action")
    public static class ActionProxyAdvice {

        private static final TimerName timerName = Agent.getTimerName(ActionProxyAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindReceiver ActionProxy actionProxy) {
            Class<?> actionClass = actionProxy.getAction().getClass();
            String actionMethod = actionProxy.getMethod();
            String methodName = actionMethod != null ? actionMethod : "execute";
            context.setTransactionName(actionClass.getSimpleName() + "#" + methodName,
                    Priority.CORE_PLUGIN);
            return context.startTraceEntry(MessageSupplier.create("struts action: {}.{}()",
                    actionClass.getName(), methodName), timerName);
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

    @Pointcut(className = "org.apache.struts.action.Action", methodName = "execute",
            methodParameterTypes = {"org.apache.struts.action.ActionMapping",
                    "org.apache.struts.action.ActionForm", ".."},
            nestingGroup = "struts", timerName = "struts action")
    public static class ActionAdvice {

        private static final TimerName timerName = Agent.getTimerName(ActionAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context, @BindReceiver Object action) {
            Class<?> actionClass = action.getClass();
            context.setTransactionName(actionClass.getSimpleName() + "#execute",
                    Priority.CORE_PLUGIN);
            return context.startTraceEntry(
                    MessageSupplier.create("struts action: {}.execute()", actionClass.getName()),
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
}
