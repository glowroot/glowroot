/*
 * Copyright 2012-2014 the original author or authors.
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
package org.glowroot.sandbox.ui;

import java.util.Map;
import java.util.Random;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.Message;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.Optional;
import org.glowroot.api.PluginServices;
import org.glowroot.api.Span;
import org.glowroot.api.MetricName;
import org.glowroot.api.weaving.BindClassMeta;
import org.glowroot.api.weaving.BindReceiver;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.Pointcut;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ExpensiveCallAspect {

    private static final PluginServices pluginServices = PluginServices.get("glowroot-ui-sandbox");

    private static final Random random = new Random();
    private static final Exception nestedCause = new IllegalArgumentException(
            "a cause with a different stack trace");
    private static final Exception cause = new IllegalStateException(
            "a cause with a different stack trace", nestedCause);

    @Pointcut(className = "org.glowroot.sandbox.ui.ExpensiveCall", methodName = "execute0",
            metricName = "expensive 0")
    public static class ExpensiveCallAdvice0 {
        private static final MetricName metricName =
                pluginServices.getMetricName(ExpensiveCallAdvice0.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            // not delegating to onBeforeInternal(), this span returns message supplier with detail
            MessageSupplier messageSupplier =
                    getMessageSupplierWithDetail(expensiveCall, expensiveCallInvoker);
            return pluginServices.startSpan(messageSupplier, metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Span span) {
            if (random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 0);
            }
        }
    }

    @Pointcut(className = "org.glowroot.sandbox.ui.ExpensiveCall", methodName = "execute1",
            metricName = "expensive 1")
    public static class ExpensiveCallAdvice1 {
        private static final MetricName metricName =
                pluginServices.getMetricName(ExpensiveCallAdvice1.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            return onBeforeInternal(expensiveCall, expensiveCallInvoker, metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Span span) {
            if (span != null && random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 1);
            }
        }
    }

    @Pointcut(className = "org.glowroot.sandbox.ui.ExpensiveCall", methodName = "execute2",
            metricName = "expensive 2")
    public static class ExpensiveCallAdvice2 {
        private static final MetricName metricName =
                pluginServices.getMetricName(ExpensiveCallAdvice2.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            return onBeforeInternal(expensiveCall, expensiveCallInvoker, metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Span span) {
            if (span != null && random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 2);
            }
        }
    }

    @Pointcut(className = "org.glowroot.sandbox.ui.ExpensiveCall", methodName = "execute3",
            metricName = "expensive 3")
    public static class ExpensiveCallAdvice3 {
        private static final MetricName metricName =
                pluginServices.getMetricName(ExpensiveCallAdvice3.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            return onBeforeInternal(expensiveCall, expensiveCallInvoker, metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Span span) {
            if (span != null && random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 3);
            }
        }
    }

    @Pointcut(className = "org.glowroot.sandbox.ui.ExpensiveCall", methodName = "execute4",
            metricName = "expensive 4")
    public static class ExpensiveCallAdvice4 {
        private static final MetricName metricName =
                pluginServices.getMetricName(ExpensiveCallAdvice4.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            return onBeforeInternal(expensiveCall, expensiveCallInvoker, metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Span span) {
            if (span != null && random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 4);
            }
        }
    }

    @Pointcut(className = "org.glowroot.sandbox.ui.ExpensiveCall", methodName = "execute5",
            metricName = "expensive 5")
    public static class ExpensiveCallAdvice5 {
        private static final MetricName metricName =
                pluginServices.getMetricName(ExpensiveCallAdvice5.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            return onBeforeInternal(expensiveCall, expensiveCallInvoker, metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Span span) {
            if (span != null && random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 5);
            }
        }
    }

    @Pointcut(className = "org.glowroot.sandbox.ui.ExpensiveCall", methodName = "execute6",
            metricName = "expensive 6")
    public static class ExpensiveCallAdvice6 {
        private static final MetricName metricName =
                pluginServices.getMetricName(ExpensiveCallAdvice6.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            return onBeforeInternal(expensiveCall, expensiveCallInvoker, metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Span span) {
            if (span != null && random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 6);
            }
        }
    }

    @Pointcut(className = "org.glowroot.sandbox.ui.ExpensiveCall", methodName = "execute7",
            metricName = "expensive 7")
    public static class ExpensiveCallAdvice7 {
        private static final MetricName metricName =
                pluginServices.getMetricName(ExpensiveCallAdvice7.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            return onBeforeInternal(expensiveCall, expensiveCallInvoker, metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Span span) {
            if (span != null && random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 7);
            }
        }
    }

    @Pointcut(className = "org.glowroot.sandbox.ui.ExpensiveCall", methodName = "execute8",
            metricName = "expensive 8")
    public static class ExpensiveCallAdvice8 {
        private static final MetricName metricName =
                pluginServices.getMetricName(ExpensiveCallAdvice8.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            return onBeforeInternal(expensiveCall, expensiveCallInvoker, metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Span span) {
            if (span != null && random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 8);
            }
        }
    }

    @Pointcut(className = "org.glowroot.sandbox.ui.ExpensiveCall", methodName = "execute9",
            metricName = "expensive 9 really long to test wrapping")
    public static class ExpensiveCallAdvice9 {
        private static final MetricName metricName =
                pluginServices.getMetricName(ExpensiveCallAdvice9.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@BindReceiver Object expensiveCall,
                @BindClassMeta ExpensiveCallInvoker expensiveCallInvoker) {
            return onBeforeInternal(expensiveCall, expensiveCallInvoker, metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Span span) {
            if (span != null && random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 9);
            }
        }
    }

    private static Span onBeforeInternal(Object expensiveCall,
            ExpensiveCallInvoker expensiveCallInvoker, MetricName metricName) {
        if (random.nextDouble() < 0.05) {
            return null;
        }
        MessageSupplier messageSupplier =
                MessageSupplier.from(expensiveCallInvoker.getSpanMessage(expensiveCall));
        return pluginServices.startSpan(messageSupplier, metricName);
    }

    private static void onAfterInternal(Span span, int num) {
        double value = random.nextDouble();
        if (span == null) {
            if (value < 0.33) {
                pluginServices.addErrorSpan(ErrorMessage.from(
                        new IllegalStateException("Exception in execute" + num
                                + ", with no span text and no custom error message",
                                getRandomCause())));
            } else if (value < 0.67) {
                pluginServices.addErrorSpan(ErrorMessage.from("randomized error with no span text",
                        new IllegalStateException("Exception in execute" + num
                                + ", with no span text", getRandomCause())));
            } else {
                pluginServices.addErrorSpan(ErrorMessage.withDetail(
                        "randomized error with no span text, with detail map",
                        new IllegalStateException("Exception in execute" + num, getRandomCause()),
                        ImmutableMap.of("x", Optional.absent(), "y", "a non-null value")));
            }
            return;
        }
        if (value < 0.94) {
            span.end();
        } else if (value < 0.96) {
            span.endWithError(ErrorMessage.from(
                    new IllegalStateException("Exception in execute" + num
                            + ", with no custom error message", getRandomCause())));
        } else if (value < 0.98) {
            span.endWithError(ErrorMessage.from("randomized error",
                    new IllegalStateException("Exception in execute" + num, getRandomCause())));
        } else {
            // add detail map to half of randomized errors
            span.endWithError(ErrorMessage.withDetail("randomized error with detail map",
                    new IllegalStateException("Exception in execute" + num, getRandomCause()),
                    ImmutableMap.of("x", Optional.absent(), "y", "a non-null value")));
        }
    }

    private static MessageSupplier getMessageSupplierWithDetail(final Object expensiveCall,
            final ExpensiveCallInvoker expensiveCallInvoker) {
        return new MessageSupplier() {
            @Override
            public Message get() {
                Map<String, ?> detail = ImmutableMap.of("attr1", "value1", "attr2", "value2",
                        "attr3", ImmutableMap.of("attr31",
                                ImmutableMap.of("attr311", ImmutableList.of("v311aa", "v311bb")),
                                "attr32", "value32", "attr33", "value33"));
                String spanMessage = expensiveCallInvoker.getSpanMessage(expensiveCall);
                return Message.withDetail(spanMessage, detail);
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
