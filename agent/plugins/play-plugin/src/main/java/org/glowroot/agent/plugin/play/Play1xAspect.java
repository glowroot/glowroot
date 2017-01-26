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

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext.Priority;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindClassMeta;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class Play1xAspect {

    @Pointcut(className = "play.mvc.ActionInvoker", methodName = "invoke",
            methodParameterTypes = {"play.mvc.Http$Request", "play.mvc.Http$Response"},
            timerName = "http request")
    public static class ActionInvokerAdvice {

        private static final TimerName timerName = Agent.getTimerName(ActionInvokerAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(OptionalThreadContext context) {
            return context.startTraceEntry(MessageSupplier.create("play action invoker"),
                    timerName);
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
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(t);
        }
    }
}
