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
 * Aspect to weave generic methods inherited from GenericParentA.
 * This FORCES the weaver to call overrideAndWeaveInheritedMethod with GenericTypeResolver
 * to match generic method signatures like process(T) with specialized signatures like process(String).
 */
public class InheritedGenericMethodAspect {

    @Pointcut(className = "org.glowroot.agent.tests.app.GenericParentA",
            methodName = "process",
            methodParameterTypes = {"java.lang.Object"},
            timerName = "inherited generic process")
    public static class ProcessAdvice {

        private static final TimerName timerName = Agent.getTimerName(ProcessAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Object value) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("Inherited Process: " +
                            (value != null ? value.toString() : "null"));
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.GenericParentA",
            methodName = "transform",
            methodParameterTypes = {"java.lang.Object"},
            timerName = "inherited generic transform")
    public static class TransformAdvice {

        private static final TimerName timerName = Agent.getTimerName(TransformAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Object input) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("Inherited Transform: " +
                            (input != null ? input.toString() : "null"));
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.GenericParentB",
            methodName = "processSpecific",
            methodParameterTypes = {"java.lang.Object"},
            timerName = "inherited processSpecific")
    public static class ProcessSpecificAdvice {

        private static final TimerName timerName = Agent.getTimerName(ProcessSpecificAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Object value) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("Inherited ProcessSpecific: " +
                            (value != null ? value.toString() : "null"));
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    /**
     * CRITICAL: This pointcut has subTypeRestriction to ConcreteChild.
     * This means the advice will NOT be applied to GenericParentB,
     * but WILL be applied when ConcreteChild is analyzed.
     * This triggers methodsThatOnlyNowFulfillAdvice!
     */
    @Pointcut(className = "org.glowroot.agent.tests.app.GenericParentB",
            methodName = "specializedOnlyInChild",
            methodParameterTypes = {"java.lang.Object"},
            //methodParameterTypes = {"java.lang.String"},
            subTypeRestriction = "org.glowroot.agent.tests.app.ConcreteChild",
            timerName = "specialized only in child")
    public static class SpecializedOnlyInChildAdvice {

        private static final TimerName timerName = Agent.getTimerName(SpecializedOnlyInChildAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Object value) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("Specialized OnlyInChild: " +
                            (value != null ? value.toString() : "null"));
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }
}

