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
 * Aspect to weave generic methods in non-generic classes.
 * Tests that method-level generic parameters are correctly handled during weaving.
 */
public class MethodLevelGenericAspect {

    @Pointcut(className = "org.glowroot.agent.tests.app.NonGenericParentWithGenericMethods",
            methodName = "identity",
            methodParameterTypes = {"java.lang.Object"},
            timerName = "method generic identity")
    public static class IdentityAdvice {
        private static final TimerName timerName = Agent.getTimerName(IdentityAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Object value) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("Method Generic Identity: " + value);
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.NonGenericParentWithGenericMethods",
            methodName = "processNumber",
            methodParameterTypes = {"java.lang.Number"},
            timerName = "method generic process number")
    public static class ProcessNumberAdvice {
        private static final TimerName timerName = Agent.getTimerName(ProcessNumberAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Number value) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("Method Generic ProcessNumber: " + value);
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.NonGenericParentWithGenericMethods",
            methodName = "formatPair",
            methodParameterTypes = {"java.lang.Object", "java.lang.Object"},
            timerName = "method generic format pair")
    public static class FormatPairAdvice {
        private static final TimerName timerName = Agent.getTimerName(FormatPairAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Object key,
                @BindParameter final Object value) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("Method Generic FormatPair: " + key + ", " + value);
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.NonGenericParentWithGenericMethods",
            methodName = "transform",
            methodParameterTypes = {"java.lang.Object"},
            timerName = "method generic transform")
    public static class TransformAdvice {
        private static final TimerName timerName = Agent.getTimerName(TransformAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Object input) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("Method Generic Transform: " + input);
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.NonGenericParentWithGenericMethods",
            methodName = "findMax",
            methodParameterTypes = {"java.lang.Number", "java.lang.Number"},
            timerName = "method generic find max")
    public static class FindMaxAdvice {
        private static final TimerName timerName = Agent.getTimerName(FindMaxAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Number first,
                @BindParameter final Number second) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("Method Generic FindMax: " + first + ", " + second);
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }
}

