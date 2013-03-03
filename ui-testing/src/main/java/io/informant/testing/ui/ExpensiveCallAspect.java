/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.testing.ui;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import io.informant.api.ErrorMessage;
import io.informant.api.Message;
import io.informant.api.MessageSupplier;
import io.informant.api.Metric;
import io.informant.api.PluginServices;
import io.informant.api.Span;
import io.informant.api.UnresolvedMethod;
import io.informant.api.weaving.InjectTarget;
import io.informant.api.weaving.InjectTraveler;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.Pointcut;

import java.util.Map;
import java.util.Random;

import checkers.nullness.quals.Nullable;

import com.google.common.collect.ImmutableMap;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ExpensiveCallAspect {

    private static final PluginServices pluginServices =
            PluginServices.get("io.informant:informant-ui-testing");

    private static final UnresolvedMethod getHeadline =
            UnresolvedMethod.from("io.informant.testing.ui.ExpensiveCall", "getHeadline");

    private static final Random random = new Random();
    private static final Exception nestedCause = new IllegalArgumentException(
            "a cause with a different stack trace");
    private static final Exception cause = new IllegalStateException(
            "a cause with a different stack trace", nestedCause);

    @Pointcut(typeName = "io.informant.testing.ui.ExpensiveCall", methodName = "execute0",
            metricName = "expensive 0")
    public static class ExpensiveCallAdvice0 {
        private static final Metric metric = pluginServices.getMetric(ExpensiveCallAdvice0.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@InjectTarget Object expensive) {
            // not delegating to onBeforeInternal(), this span returns message supplier with detail
            return pluginServices.startSpan(getMessageSupplierWithDetail(expensive), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            if (random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 0);
            }
        }
    }

    @Pointcut(typeName = "io.informant.testing.ui.ExpensiveCall", methodName = "execute1",
            metricName = "expensive 1")
    public static class ExpensiveCallAdvice1 {
        private static final Metric metric = pluginServices.getMetric(ExpensiveCallAdvice1.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@InjectTarget Object expensive) {
            return onBeforeInternal(expensive, metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler @Nullable Span span) {
            if (span != null && random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 1);
            }
        }
    }

    @Pointcut(typeName = "io.informant.testing.ui.ExpensiveCall", methodName = "execute2",
            metricName = "expensive 2")
    public static class ExpensiveCallAdvice2 {
        private static final Metric metric = pluginServices.getMetric(ExpensiveCallAdvice2.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@InjectTarget Object expensive) {
            return onBeforeInternal(expensive, metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler @Nullable Span span) {
            if (span != null && random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 2);
            }
        }
    }

    @Pointcut(typeName = "io.informant.testing.ui.ExpensiveCall", methodName = "execute3",
            metricName = "expensive 3")
    public static class ExpensiveCallAdvice3 {
        private static final Metric metric = pluginServices.getMetric(ExpensiveCallAdvice3.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@InjectTarget Object expensive) {
            return onBeforeInternal(expensive, metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler @Nullable Span span) {
            if (span != null && random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 3);
            }
        }
    }

    @Pointcut(typeName = "io.informant.testing.ui.ExpensiveCall", methodName = "execute4",
            metricName = "expensive 4")
    public static class ExpensiveCallAdvice4 {
        private static final Metric metric = pluginServices.getMetric(ExpensiveCallAdvice4.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@InjectTarget Object expensive) {
            return onBeforeInternal(expensive, metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler @Nullable Span span) {
            if (span != null && random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 4);
            }
        }
    }

    @Pointcut(typeName = "io.informant.testing.ui.ExpensiveCall", methodName = "execute5",
            metricName = "expensive 5")
    public static class ExpensiveCallAdvice5 {
        private static final Metric metric = pluginServices.getMetric(ExpensiveCallAdvice5.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@InjectTarget Object expensive) {
            return onBeforeInternal(expensive, metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler @Nullable Span span) {
            if (span != null && random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 5);
            }
        }
    }

    @Pointcut(typeName = "io.informant.testing.ui.ExpensiveCall", methodName = "execute6",
            metricName = "expensive 6")
    public static class ExpensiveCallAdvice6 {
        private static final Metric metric = pluginServices.getMetric(ExpensiveCallAdvice6.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@InjectTarget Object expensive) {
            return onBeforeInternal(expensive, metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler @Nullable Span span) {
            if (span != null && random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 6);
            }
        }
    }

    @Pointcut(typeName = "io.informant.testing.ui.ExpensiveCall", methodName = "execute7",
            metricName = "expensive 7")
    public static class ExpensiveCallAdvice7 {
        private static final Metric metric = pluginServices.getMetric(ExpensiveCallAdvice7.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@InjectTarget Object expensive) {
            return onBeforeInternal(expensive, metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler @Nullable Span span) {
            if (span != null && random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 7);
            }
        }
    }

    @Pointcut(typeName = "io.informant.testing.ui.ExpensiveCall", methodName = "execute8",
            metricName = "expensive 8")
    public static class ExpensiveCallAdvice8 {
        private static final Metric metric = pluginServices.getMetric(ExpensiveCallAdvice8.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@InjectTarget Object expensive) {
            return onBeforeInternal(expensive, metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler @Nullable Span span) {
            if (span != null && random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 8);
            }
        }
    }

    @Pointcut(typeName = "io.informant.testing.ui.ExpensiveCall", methodName = "execute9",
            metricName = "expensive 9")
    public static class ExpensiveCallAdvice9 {
        private static final Metric metric = pluginServices.getMetric(ExpensiveCallAdvice9.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@InjectTarget Object expensive) {
            return onBeforeInternal(expensive, metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler @Nullable Span span) {
            if (span != null && random.nextDouble() < 0.05) {
                // Span.endWithStackTrace() must be called directly from @On.. method so it can
                // strip back the stack trace to the method picked out by the @Pointcut
                span.endWithStackTrace(0, NANOSECONDS);
            } else {
                onAfterInternal(span, 9);
            }
        }
    }

    @Nullable
    private static Span onBeforeInternal(Object expensive, Metric metric) {
        if (random.nextDouble() < 0.05) {
            return null;
        }
        return pluginServices.startSpan(getMessageSupplier(expensive), metric);
    }

    private static void onAfterInternal(@Nullable Span span, int num) {
        if (span == null) {
            pluginServices.addErrorSpan(ErrorMessage.from("randomized error with no span message",
                    new IllegalStateException("Exception in execute" + num, cause)));
            return;
        }
        double value = random.nextDouble();
        if (value < 0.95) {
            span.end();
        } else {
            span.endWithError(ErrorMessage.from("randomized error", new IllegalStateException(
                    "Exception in execute" + num, cause)));
        }
    }

    private static MessageSupplier getMessageSupplier(Object expensive) {
        String headline = (String) getHeadline.invoke(expensive,
                "<error calling ExpensiveCall.getHeadline()>");
        return MessageSupplier.from(headline);
    }

    private static MessageSupplier getMessageSupplierWithDetail(final Object expensive) {
        return new MessageSupplier() {
            @Override
            public Message get() {
                Map<String, ?> detail = ImmutableMap.of("attr1", "value1", "attr2", "value2",
                        "attr3", ImmutableMap.of("attr31",
                                ImmutableMap.of("attr311", "value311", "attr312", "value312"),
                                "attr32", "value32", "attr33", "value33"));
                String headline = (String) getHeadline.invoke(expensive,
                        "<error calling ExpensiveCall.getHeadline()>");
                return Message.withDetail(headline, detail);
            }
        };
    }
}
