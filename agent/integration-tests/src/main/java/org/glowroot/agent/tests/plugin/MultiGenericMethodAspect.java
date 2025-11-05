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
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

/**
 * Aspect to weave multi-generic type methods.
 * Tests generic specialization with two and three type parameters.
 */
public class MultiGenericMethodAspect {

    // ========== TWO TYPE PARAMETERS ==========

    @Pointcut(className = "org.glowroot.agent.tests.app.GenericParentTwoTypes",
            methodName = "processFirst",
            methodParameterTypes = {"java.lang.Object"},
            timerName = "multi generic process first")
    public static class ProcessFirstAdvice {
        private static final TimerName timerName = Agent.getTimerName(ProcessFirstAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Object value) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("Multi Generic ProcessFirst: " + value);
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.GenericParentTwoTypes",
            methodName = "processSecond",
            methodParameterTypes = {"java.lang.Object"},
            timerName = "multi generic process second")
    public static class ProcessSecondAdvice {
        private static final TimerName timerName = Agent.getTimerName(ProcessSecondAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Object value) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("Multi Generic ProcessSecond: " + value);
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.GenericParentTwoTypes",
            methodName = "transformSecondToFirst",
            methodParameterTypes = {"java.lang.Object"},
            timerName = "transform second to first")
    public static class TransformSecondToFirstAdvice {
        private static final TimerName timerName = Agent.getTimerName(TransformSecondToFirstAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Object input) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("TransformSecondToFirst: " + input);
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    // ========== THREE TYPE PARAMETERS ==========

    @Pointcut(className = "org.glowroot.agent.tests.app.GenericParentThreeTypes",
            methodName = "processThird",
            methodParameterTypes = {"java.lang.Object"},
            timerName = "multi generic process third")
    public static class ProcessThirdAdvice {
        private static final TimerName timerName = Agent.getTimerName(ProcessThirdAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Object value) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("Multi Generic ProcessThird: " + value);
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.GenericParentThreeTypes",
            methodName = "transformTtoR",
            methodParameterTypes = {"java.lang.Object"},
            timerName = "transform T to R")
    public static class TransformTtoRAdvice {
        private static final TimerName timerName = Agent.getTimerName(TransformTtoRAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Object input) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("TransformTtoR: " + input);
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.GenericMiddleThreeTypes",
            methodName = "processThird",
            methodParameterTypes = {"java.lang.Boolean"},
            timerName = "middle generic process third")
    public static class MiddleProcessThirdAdvice {
        private static final TimerName timerName = Agent.getTimerName(MiddleProcessThirdAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Boolean value) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("Middle Generic ProcessThird: " + value);
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    // ========== BOUNDED TYPE PARAMETERS ==========

    @Pointcut(className = "org.glowroot.agent.tests.app.GenericParentBounded",
            methodName = "multiplyByTwo",
            methodParameterTypes = {"java.lang.Number"},
            timerName = "multiply by two")
    public static class MultiplyByTwoAdvice {
        private static final TimerName timerName = Agent.getTimerName(MultiplyByTwoAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Number input) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("MultiplyByTwo: " + input);
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    // ========== MULTIPLE BOUNDS ==========

    @Pointcut(className = "org.glowroot.agent.tests.app.GenericParentMultipleBounds",
            methodName = "findMax",
            methodParameterTypes = {"java.lang.Number", "java.lang.Number"},
            timerName = "find max")
    public static class FindMaxAdvice {
        private static final TimerName timerName = Agent.getTimerName(FindMaxAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Number first,
                @BindParameter final Number second) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("FindMax: " + first + ", " + second);
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.GenericParentMultipleBounds",
            methodName = "compareValues",
            methodParameterTypes = {"java.lang.Number", "java.lang.Number"},
            timerName = "compare values")
    public static class CompareValuesAdvice {
        private static final TimerName timerName = Agent.getTimerName(CompareValuesAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Number first,
                @BindParameter final Number second) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("CompareValues: " + first + ", " + second);
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    // ========== NESTED GENERICS ==========

    @Pointcut(className = "org.glowroot.agent.tests.app.GenericParentNested",
            methodName = "filterList",
            methodParameterTypes = {"java.util.List"},
            timerName = "filter list")
    public static class FilterListAdvice {
        private static final TimerName timerName = Agent.getTimerName(FilterListAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final java.util.List<?> input) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("FilterList: size=" + (input != null ? input.size() : 0));
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    // ========== KEY-VALUE GENERICS ==========

    @Pointcut(className = "org.glowroot.agent.tests.app.GenericParentKeyValue",
            methodName = "processEntry",
            methodParameterTypes = {"java.lang.Object", "java.lang.Object"},
            timerName = "process entry")
    public static class ProcessEntryAdvice {
        private static final TimerName timerName = Agent.getTimerName(ProcessEntryAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Object key,
                @BindParameter final Object value) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("ProcessEntry: " + key + " = " + value);
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.GenericParentKeyValue",
            methodName = "get",
            methodParameterTypes = {"java.lang.Object"},
            timerName = "key value get")
    public static class KeyValueGetAdvice {
        private static final TimerName timerName = Agent.getTimerName(KeyValueGetAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Object key) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("Get: " + key);
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.GenericParentKeyValue",
            methodName = "put",
            methodParameterTypes = {"java.lang.Object", "java.lang.Object"},
            timerName = "key value put")
    public static class KeyValuePutAdvice {
        private static final TimerName timerName = Agent.getTimerName(KeyValuePutAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter final Object key,
                @BindParameter final Object value) {
            return context.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.create("Put: " + key + " = " + value);
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }
}
