/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.testing.ui;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.informantproject.api.Message;
import org.informantproject.api.MessageSuppliers;
import org.informantproject.api.Metric;
import org.informantproject.api.PluginServices;
import org.informantproject.api.Span;
import org.informantproject.api.Supplier;
import org.informantproject.api.Suppliers;
import org.informantproject.api.weaving.Aspect;
import org.informantproject.api.weaving.InjectTraveler;
import org.informantproject.api.weaving.IsEnabled;
import org.informantproject.api.weaving.OnAfter;
import org.informantproject.api.weaving.OnBefore;
import org.informantproject.api.weaving.Pointcut;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Aspect
public class NestableCallAspect {

    private static final ImmutableList<String> USERNAMES = ImmutableList.of("able", "baker",
            "charlie");

    private static final AtomicInteger counter = new AtomicInteger();

    private static final PluginServices pluginServices = PluginServices
            .get("org.informantproject:informant-ui-testing");

    @Pointcut(typeName = "org.informantproject.testing.ui.NestableCall", methodName = "execute",
            metricName = "nestable")
    public static class LevelOneAdvice {
        private static final Metric metric = pluginServices.getMetric(LevelOneAdvice.class);
        private static final Random random = new Random();
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && pluginServices.getRootMessageSupplier() == null;
        }
        @OnBefore
        public static Span onBefore() {
            Span span = pluginServices.startTrace(getRootMessageSupplier(), metric);
            int index = counter.getAndIncrement() % (USERNAMES.size() + 1);
            if (index < USERNAMES.size()) {
                pluginServices.setUsername(Suppliers.ofInstance(USERNAMES.get(index)));
            } else {
                pluginServices.setUsername(Suppliers.ofInstance((String) null));
            }
            return span;
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            if (random.nextDouble() < 0.9) {
                span.end();
            } else {
                span.endWithError(null);
            }
        }
    }

    @Pointcut(typeName = "org.informantproject.testing.ui.NestableCall", methodName = "execute",
            metricName = "nestable and very long")
    public static class LevelOneLongMetricAdvice {
        private static final Metric metric = pluginServices
                .getMetric(LevelOneLongMetricAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && pluginServices.getRootMessageSupplier() != null;
        }
        @OnBefore
        public static Span onBefore() {
            pluginServices.putTraceAttribute("my first attribute", "hello world");
            pluginServices.putTraceAttribute("and second", "val");
            pluginServices.putTraceAttribute("and a very long attribute value", "abcdefghijkl"
                    + "mnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz");
            pluginServices.putTraceAttribute("and another", "a b c d e f g h i j k l m n o p q"
                    + " r s t u v w x y z a b c d e f g h i j k l m n o p q r s t u v w x y z");
            return pluginServices.startSpan(MessageSuppliers.of("Nestable"), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }

    private static Supplier<Message> getRootMessageSupplier() {
        return new Supplier<Message>() {
            public Message get() {
                Map<String, ?> contextMap = ImmutableMap.of("attr1", "value1", "attr2", "value2",
                        "attr3", ImmutableMap.of("attr31",
                                ImmutableMap.of("attr311", "value311", "attr312", "value312"),
                                "attr32", "value32", "attr33", "value33"));
                return Message.of("Nestable with a very long description to test"
                        + " wrapping abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz",
                        contextMap);
            }
        };
    }
}
