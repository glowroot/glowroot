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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.informantproject.api.ContextMap;
import org.informantproject.api.Message;
import org.informantproject.api.MessageSupplier;
import org.informantproject.api.Metric;
import org.informantproject.api.PluginServices;
import org.informantproject.api.Stopwatch;
import org.informantproject.api.Supplier;
import org.informantproject.api.SupplierOfNullable;
import org.informantproject.api.weaving.Aspect;
import org.informantproject.api.weaving.InjectTraveler;
import org.informantproject.api.weaving.IsEnabled;
import org.informantproject.api.weaving.OnAfter;
import org.informantproject.api.weaving.OnBefore;
import org.informantproject.api.weaving.Pointcut;

import com.google.common.collect.ImmutableList;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Aspect
public class NestableCallAspect {

    private static final List<String> USERNAMES = ImmutableList.of("able",
            "baker", "charlie");

    private static final AtomicInteger counter = new AtomicInteger();

    private static final PluginServices pluginServices = PluginServices
            .get("org.informantproject:informant-ui-testing");

    @Pointcut(typeName = "org.informantproject.testing.ui.NestableCall", methodName = "execute",
            metricName = "nestable")
    public static class LevelOneAdvice {
        private static final Metric metric = pluginServices.getMetric(LevelOneAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && pluginServices.getRootMessageSupplier() == null;
        }
        @OnBefore
        public static Stopwatch onBefore() {
            Stopwatch stopwatch = pluginServices.startTrace(getRootMessageSupplier(), metric);
            int index = counter.getAndIncrement() % (USERNAMES.size() + 1);
            if (index < USERNAMES.size()) {
                pluginServices.setUsername(SupplierOfNullable.ofInstance(USERNAMES.get(index)));
            } else {
                pluginServices.setUsername(SupplierOfNullable.ofInstance((String) null));
            }
            return stopwatch;
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Stopwatch stopwatch) {
            stopwatch.stop();
        }
    }

    @Pointcut(typeName = "org.informantproject.testing.ui.NestableCall", methodName = "execute",
            metricName = "nestable and very long")
    public static class LevelOneLongMetricAdvice {
        private static final Metric metric = pluginServices.getMetric(
                LevelOneLongMetricAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && pluginServices.getRootMessageSupplier() != null;
        }
        @OnBefore
        public static Stopwatch onBefore() {
            pluginServices.putTraceAttribute("my first attribute", "hello world");
            pluginServices.putTraceAttribute("and second", "val");
            pluginServices.putTraceAttribute("and a very long attribute value", "abcdefghijkl"
                    + "mnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz");
            pluginServices.putTraceAttribute("and another", "a b c d e f g h i j k l m n o p q"
                    + " r s t u v w x y z a b c d e f g h i j k l m n o p q r s t u v w x y z");
            return pluginServices.startEntry(MessageSupplier.of("Nestable"), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Stopwatch stopwatch) {
            stopwatch.stop();
        }
    }

    private static Supplier<Message> getRootMessageSupplier() {
        return new Supplier<Message>() {
            @Override
            public Message get() {
                ContextMap context = ContextMap.of("attr1", "value1", "attr2", "value2",
                        "attr3", ContextMap.of("attr31", ContextMap.of("attr311", "value311",
                                "attr312", "value312"), "attr32", "value32", "attr33", "value33"));
                return Message.withContext("Nestable with a very long description to test wrapping"
                        + " abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz", context);
            }
        };
    }
}
