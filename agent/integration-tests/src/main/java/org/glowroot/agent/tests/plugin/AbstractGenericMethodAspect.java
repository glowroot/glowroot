/*
 * Copyright 2025 the original author or authors.
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
package org.glowroot.agent.tests.plugin;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.Message;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

/**
 * Aspect to weave abstract generic method from GenericParentA.
 * This FORCES the weaver to call overrideAndWeaveInheritedMethod and use GenericTypeResolver
 * to resolve the generic signature validateAndProcess(T) to validateAndProcess(String) in ConcreteChild.
 */
public class AbstractGenericMethodAspect {

    @Pointcut(className = "org.glowroot.agent.tests.app.GenericParentA",
            methodName = "validateAndProcess",
            methodParameterTypes = {"java.lang.Object"},
            timerName = "abstract generic validate")
    public static class ValidateAndProcessAdvice {

        private static final TimerName timerName = Agent.getTimerName(ValidateAndProcessAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Object input) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("Abstract ValidateAndProcess: " +
                            (input != null ? input.toString() : "null"));
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }
}

