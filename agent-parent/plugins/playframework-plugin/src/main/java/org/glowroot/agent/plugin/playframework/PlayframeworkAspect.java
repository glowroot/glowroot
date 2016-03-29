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
package org.glowroot.agent.plugin.playframework;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext.Priority;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

import java.lang.reflect.Field;

public class PlayframeworkAspect {


    @Pointcut(className = "play.mvc.ActionInvoker", methodName = "invoke",
            methodParameterTypes = {"play.mvc.Http$Request", "play.mvc.Http$Response"},
            timerName = "playframework action invoker")
    public static class ActionInvokerAdvice {
        private static final TimerName timerName = Agent.getTimerName(ActionInvokerAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context ) {
            return context.startTraceEntry(MessageSupplier.from("playframework action invoker"), timerName);
        }

        @OnReturn
        public static void onReturn(ThreadContext context, @BindTraveler TraceEntry traceEntry,@BindParameter Object request,
                                    @SuppressWarnings("unused") @BindParameter Object response) {
            try {
                Field field = Class.forName("play.mvc.Http$Request").getField("action");
                String transactionName = (String)field.get(request);
                context.setTransactionName(transactionName, Priority.CORE_PLUGIN);
            } catch (NoSuchFieldException e) {
                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
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
