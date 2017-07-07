/*
 * Copyright 2012-2017 the original author or authors.
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
package org.glowroot.agent.ui.sandbox;

import java.util.Map;
import java.util.Random;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.QueryEntry;
import org.glowroot.agent.plugin.api.QueryMessage;
import org.glowroot.agent.plugin.api.QueryMessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindClassMeta;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

public class ExpensiveCallAspect {

    private static final Random random = new Random();
    private static final Exception nestedCause =
            new IllegalArgumentException("A cause with a different stack trace");
    private static final Exception cause =
            new IllegalStateException("A cause with a different stack trace", nestedCause);

    @Pointcut(className = "org.glowroot.agent.ui.sandbox.ExpensiveCall", methodName = "execute0",
            methodParameterTypes = {}, timerName = "expensive 0")
    public static class ExpensiveCallAdvice0 {
        private static final TimerName timerName = Agent.getTimerName(ExpensiveCallAdvice0.class);
        @OnBefore
        public static QueryEntry onBefore(ThreadContext context, @BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            // not delegating to onBeforeInternal(), this pointcut returns message supplier with
            // detail
            QueryMessageSupplier messageSupplier =
                    getQueryMessageSupplierWithDetail(expensiveCall, expensiveCallInvoker);
            char randomChar = (char) ('a' + random.nextInt(26));
            String queryText;
            if (random.nextBoolean()) {
                queryText = "this is a short query " + randomChar;
            } else {
                queryText = "this is a long query " + randomChar
                        + " abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz"
                        + " abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz"
                        + " abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz"
                        + " abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz"
                        + " abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz";
            }
            return context.startQueryEntry("EQL", queryText, messageSupplier, timerName);
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindTraveler QueryEntry query) {
            query.incrementCurrRow();
            query.incrementCurrRow();
            query.incrementCurrRow();
            if (random.nextDouble() < 0.05) {
                // TraceEntry.endWithLocationStackTrace() must be called directly from @On.. method
                // so it can strip back the stack trace to the method picked out by the @Pointcut
                query.endWithLocationStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(context, query, 0);
            }
        }
    }

    @Pointcut(className = "org.glowroot.agent.ui.sandbox.ExpensiveCall", methodName = "execute1",
            methodParameterTypes = {}, timerName = "expensive 1")
    public static class ExpensiveCallAdvice1 {
        private static final TimerName timerName = Agent.getTimerName(ExpensiveCallAdvice1.class);
        @OnBefore
        public static TraceEntry onBefore(ThreadContext context, @BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            return onBeforeInternal(context, expensiveCall, expensiveCallInvoker, timerName);
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindTraveler TraceEntry traceEntry) {
            if (traceEntry != null && random.nextDouble() < 0.05) {
                // TraceEntry.endWithLocationStackTrace() must be called directly from @On.. method
                // so it can strip back the stack trace to the method picked out by the @Pointcut
                traceEntry.endWithLocationStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(context, traceEntry, 1);
            }
        }
    }

    @Pointcut(className = "org.glowroot.agent.ui.sandbox.ExpensiveCall", methodName = "execute2",
            methodParameterTypes = {}, timerName = "expensive 2")
    public static class ExpensiveCallAdvice2 {
        private static final TimerName timerName = Agent.getTimerName(ExpensiveCallAdvice2.class);
        @OnBefore
        public static TraceEntry onBefore(ThreadContext context, @BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            return onBeforeInternal(context, expensiveCall, expensiveCallInvoker, timerName);
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindTraveler TraceEntry traceEntry) {
            if (traceEntry != null && random.nextDouble() < 0.05) {
                // TraceEntry.endWithLocationStackTrace() must be called directly from @On.. method
                // so it can strip back the stack trace to the method picked out by the @Pointcut
                traceEntry.endWithLocationStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(context, traceEntry, 2);
            }
        }
    }

    @Pointcut(className = "org.glowroot.agent.ui.sandbox.ExpensiveCall", methodName = "execute3",
            methodParameterTypes = {}, timerName = "expensive 3")
    public static class ExpensiveCallAdvice3 {
        private static final TimerName timerName = Agent.getTimerName(ExpensiveCallAdvice3.class);
        @OnBefore
        public static TraceEntry onBefore(ThreadContext context, @BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            return onBeforeInternal(context, expensiveCall, expensiveCallInvoker, timerName);
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindTraveler TraceEntry traceEntry) {
            if (traceEntry != null && random.nextDouble() < 0.05) {
                // TraceEntry.endWithLocationStackTrace() must be called directly from @On.. method
                // so it can strip back the stack trace to the method picked out by the @Pointcut
                traceEntry.endWithLocationStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(context, traceEntry, 3);
            }
        }
    }

    @Pointcut(className = "org.glowroot.agent.ui.sandbox.ExpensiveCall", methodName = "execute4",
            methodParameterTypes = {}, timerName = "expensive 4")
    public static class ExpensiveCallAdvice4 {
        private static final TimerName timerName = Agent.getTimerName(ExpensiveCallAdvice4.class);
        @OnBefore
        public static TraceEntry onBefore(ThreadContext context, @BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            return onBeforeInternal(context, expensiveCall, expensiveCallInvoker, timerName);
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindTraveler TraceEntry traceEntry) {
            if (traceEntry != null && random.nextDouble() < 0.05) {
                // TraceEntry.endWithLocationStackTrace() must be called directly from @On.. method
                // so it can strip back the stack trace to the method picked out by the @Pointcut
                traceEntry.endWithLocationStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(context, traceEntry, 4);
            }
        }
    }

    @Pointcut(className = "org.glowroot.agent.ui.sandbox.ExpensiveCall", methodName = "execute5",
            methodParameterTypes = {}, timerName = "expensive 5")
    public static class ExpensiveCallAdvice5 {
        private static final TimerName timerName = Agent.getTimerName(ExpensiveCallAdvice5.class);
        @OnBefore
        public static TraceEntry onBefore(ThreadContext context, @BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            return onBeforeInternal(context, expensiveCall, expensiveCallInvoker, timerName);
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindTraveler TraceEntry traceEntry) {
            if (traceEntry != null && random.nextDouble() < 0.05) {
                // TraceEntry.endWithLocationStackTrace() must be called directly from @On.. method
                // so it can strip back the stack trace to the method picked out by the @Pointcut
                traceEntry.endWithLocationStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(context, traceEntry, 5);
            }
        }
    }

    @Pointcut(className = "org.glowroot.agent.ui.sandbox.ExpensiveCall", methodName = "execute6",
            methodParameterTypes = {}, timerName = "expensive 6")
    public static class ExpensiveCallAdvice6 {
        private static final TimerName timerName = Agent.getTimerName(ExpensiveCallAdvice6.class);
        @OnBefore
        public static TraceEntry onBefore(ThreadContext context, @BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            return onBeforeInternal(context, expensiveCall, expensiveCallInvoker, timerName);
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindTraveler TraceEntry traceEntry) {
            if (traceEntry != null && random.nextDouble() < 0.05) {
                // TraceEntry.endWithLocationStackTrace() must be called directly from @On.. method
                // so it can strip back the stack trace to the method picked out by the @Pointcut
                traceEntry.endWithLocationStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(context, traceEntry, 6);
            }
        }
    }

    @Pointcut(className = "org.glowroot.agent.ui.sandbox.ExpensiveCall", methodName = "execute7",
            methodParameterTypes = {}, timerName = "expensive 7")
    public static class ExpensiveCallAdvice7 {
        private static final TimerName timerName = Agent.getTimerName(ExpensiveCallAdvice7.class);
        @OnBefore
        public static TraceEntry onBefore(ThreadContext context, @BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            return onBeforeInternal(context, expensiveCall, expensiveCallInvoker, timerName);
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindTraveler TraceEntry traceEntry) {
            if (traceEntry != null && random.nextDouble() < 0.05) {
                // TraceEntry.endWithLocationStackTrace() must be called directly from @On.. method
                // so it can strip back the stack trace to the method picked out by the @Pointcut
                traceEntry.endWithLocationStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(context, traceEntry, 7);
            }
        }
    }

    @Pointcut(className = "org.glowroot.agent.ui.sandbox.ExpensiveCall", methodName = "execute8",
            methodParameterTypes = {}, timerName = "expensive 8")
    public static class ExpensiveCallAdvice8 {
        private static final TimerName timerName = Agent.getTimerName(ExpensiveCallAdvice8.class);
        @OnBefore
        public static TraceEntry onBefore(ThreadContext context, @BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            return onBeforeInternal(context, expensiveCall, expensiveCallInvoker, timerName);
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindTraveler TraceEntry traceEntry) {
            if (traceEntry != null && random.nextDouble() < 0.05) {
                // TraceEntry.endWithLocationStackTrace() must be called directly from @On.. method
                // so it can strip back the stack trace to the method picked out by the @Pointcut
                traceEntry.endWithLocationStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(context, traceEntry, 8);
            }
        }
    }

    @Pointcut(className = "org.glowroot.agent.ui.sandbox.ExpensiveCall", methodName = "execute9",
            methodParameterTypes = {}, timerName = "expensive 9 really long to test wrapping")
    public static class ExpensiveCallAdvice9 {
        private static final TimerName timerName = Agent.getTimerName(ExpensiveCallAdvice9.class);
        @OnBefore
        public static TraceEntry onBefore(ThreadContext context, @BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            return onBeforeInternal(context, expensiveCall, expensiveCallInvoker, timerName);
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindTraveler TraceEntry traceEntry) {
            if (traceEntry != null && random.nextDouble() < 0.05) {
                // TraceEntry.endWithLocationStackTrace() must be called directly from @On.. method
                // so it can strip back the stack trace to the method picked out by the @Pointcut
                traceEntry.endWithLocationStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(context, traceEntry, 9);
            }
        }
    }

    private static TraceEntry onBeforeInternal(ThreadContext context, Object expensiveCall,
            ExpensiveCallInvoker expensiveCallInvoker, TimerName timerName) {
        if (random.nextDouble() < 0.05) {
            return null;
        }
        MessageSupplier messageSupplier =
                MessageSupplier.create(expensiveCallInvoker.getTraceEntryMessage(expensiveCall));
        return context.startTraceEntry(messageSupplier, timerName);
    }

    private static void onAfterInternal(ThreadContext context, TraceEntry traceEntry, int num) {
        double value = random.nextDouble();
        if (traceEntry == null) {
            if (value < 0.5) {
                context.addErrorEntry(new IllegalStateException(
                        "Exception in execute" + num
                                + "\nwith no trace entry text and no custom error message",
                        getRandomCause()));
            } else {
                context.addErrorEntry("randomized error\nwith no trace entry text",
                        new IllegalStateException(
                                "Exception in execute" + num + "\nwith no trace entry text",
                                getRandomCause()));
            }
            return;
        }
        if (value < 0.94) {
            traceEntry.end();
        } else if (value < 0.96) {
            traceEntry.endWithError(new IllegalStateException(
                    "Exception in execute" + num + "\nwith no custom error message",
                    getRandomCause()));
        } else {
            traceEntry.endWithError("randomized error",
                    new IllegalStateException("Exception in execute" + num, getRandomCause()));
        }
    }

    private static QueryMessageSupplier getQueryMessageSupplierWithDetail(
            final Object expensiveCall, final ExpensiveCallInvoker expensiveCallInvoker) {
        return new QueryMessageSupplier() {
            @Override
            public QueryMessage get() {
                Map<String, ?> detail =
                        ImmutableMap.of("attr1", "value1\nwith newline", "attr2", "value2", "attr3",
                                ImmutableMap.of("attr31",
                                        ImmutableMap.of("attr311",
                                                ImmutableList.of("v311aa", "v311bb")),
                                        "attr32", "value32\nwith newline", "attr33", "value33"));
                String traceEntryMessage = expensiveCallInvoker.getTraceEntryMessage(expensiveCall);
                return QueryMessage.create("the query: ", " | " + traceEntryMessage, detail);
            }
        };
    }

    private static Exception getRandomCause() {
        if (random.nextBoolean()) {
            return cause;
        } else {
            return null;
        }
    }
}
