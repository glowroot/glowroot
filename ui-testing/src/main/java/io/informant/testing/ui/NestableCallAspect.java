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

import io.informant.api.ErrorMessage;
import io.informant.api.Message;
import io.informant.api.MessageSupplier;
import io.informant.api.Metric;
import io.informant.api.PluginServices;
import io.informant.api.Span;
import io.informant.api.weaving.Aspect;
import io.informant.api.weaving.InjectTraveler;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.Pointcut;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Aspect
public class NestableCallAspect {

    private static final ImmutableList<String> USER_IDS = ImmutableList.of("able", "baker",
            "charlie");

    private static final AtomicInteger counter = new AtomicInteger();

    private static final PluginServices pluginServices = PluginServices
            .get("io.informant:informant-ui-testing");

    @Pointcut(typeName = "io.informant.testing.ui.NestableCall", methodName = "execute",
            metricName = "nestable", captureNested = false)
    public static class NestableCallAdvice {
        private static final Metric metric = pluginServices.getMetric(NestableCallAdvice.class);
        private static final Random random = new Random();
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore() {
            int count = counter.getAndIncrement();
            Span span;
            if (count % 2 == 0) {
                span = pluginServices.startTrace(getRootMessageSupplier(), metric);
            } else {
                span = pluginServices.startBackgroundTrace(getRootMessageSupplier(), metric);
            }
            int index = count % (USER_IDS.size() + 1);
            if (index < USER_IDS.size()) {
                pluginServices.setUserId(USER_IDS.get(index));
            } else {
                pluginServices.setUserId(null);
            }
            return span;
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            if (random.nextDouble() < 0.8) {
                span.end();
            } else {
                span.endWithError(ErrorMessage.from("randomized error", new RuntimeException()));
            }
        }
    }

    @Pointcut(typeName = "io.informant.testing.ui.NestableCall", methodName = "execute",
            metricName = "nestable and very long", captureNested = false)
    public static class NestableCallLongMetricAdvice {
        private static final Metric metric = pluginServices
                .getMetric(NestableCallLongMetricAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore() {
            pluginServices.setTraceAttribute("my first attribute", "hello world");
            pluginServices.setTraceAttribute("and second", "val");
            pluginServices.setTraceAttribute("and a very long attribute value", "abcdefghijkl"
                    + "mnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz");
            pluginServices.setTraceAttribute("and another", "a b c d e f g h i j k l m n o p q"
                    + " r s t u v w x y z a b c d e f g h i j k l m n o p q r s t u v w x y z");
            return pluginServices.startSpan(MessageSupplier.from("Nestable"), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }

    private static MessageSupplier getRootMessageSupplier() {
        return new MessageSupplier() {
            @Override
            public Message get() {
                Map<String, ?> detail = ImmutableMap.of("attr1", getLongDetailValue(false),
                        "attr2", "value2", "attr3", ImmutableMap.of("attr31",
                                ImmutableMap.of("attr311", "value311", "attr312", "value312"),
                                "attr32", getLongDetailValue(true),
                                "attr33", getLongDetailValue(false)));
                return Message.withDetail("Nestable with a very long headline to test wrapping"
                        + " abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz", detail);
            }
        };
    }

    private static String getLongDetailValue(boolean spaces) {
        int length = new Random().nextInt(200);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(i % 10);
            if (spaces && i % 10 == 0) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }
}
