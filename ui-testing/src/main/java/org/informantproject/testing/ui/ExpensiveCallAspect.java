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

import org.informantproject.api.Message;
import org.informantproject.api.MessageSupplier;
import org.informantproject.api.Metric;
import org.informantproject.api.PluginServices;
import org.informantproject.api.Span;
import org.informantproject.api.Supplier;
import org.informantproject.api.weaving.Aspect;
import org.informantproject.api.weaving.InjectTarget;
import org.informantproject.api.weaving.InjectTraveler;
import org.informantproject.api.weaving.IsEnabled;
import org.informantproject.api.weaving.OnAfter;
import org.informantproject.api.weaving.OnBefore;
import org.informantproject.api.weaving.Pointcut;

import com.google.common.collect.ImmutableMap;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Aspect
public class ExpensiveCallAspect {

    private static final PluginServices pluginServices = PluginServices
            .get("org.informantproject:informant-ui-testing");

    @Pointcut(typeName = "org.informantproject.testing.ui.ExpensiveCall", methodName = "execute0",
            metricName = "expensive 0")
    public static class LevelOneAdvice0 {
        private static final Metric metric = pluginServices.getMetric(LevelOneAdvice0.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@InjectTarget ExpensiveCall expensive) {
            return pluginServices.startSpan(getMessageSupplierWithContextMap(expensive), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }

    @Pointcut(typeName = "org.informantproject.testing.ui.ExpensiveCall", methodName = "execute1",
            metricName = "expensive 1")
    public static class LevelOneAdvice1 {
        private static final Metric metric = pluginServices.getMetric(LevelOneAdvice1.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@InjectTarget ExpensiveCall expensive) {
            return pluginServices.startSpan(getMessageSupplier(expensive), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }

    @Pointcut(typeName = "org.informantproject.testing.ui.ExpensiveCall", methodName = "execute2",
            metricName = "expensive 2")
    public static class LevelOneAdvice2 {
        private static final Metric metric = pluginServices.getMetric(LevelOneAdvice2.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@InjectTarget ExpensiveCall expensive) {
            return pluginServices.startSpan(getMessageSupplier(expensive), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }

    @Pointcut(typeName = "org.informantproject.testing.ui.ExpensiveCall", methodName = "execute3",
            metricName = "expensive 3")
    public static class LevelOneAdvice3 {
        private static final Metric metric = pluginServices.getMetric(LevelOneAdvice3.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@InjectTarget ExpensiveCall expensive) {
            return pluginServices.startSpan(getMessageSupplier(expensive), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }

    @Pointcut(typeName = "org.informantproject.testing.ui.ExpensiveCall", methodName = "execute4",
            metricName = "expensive 4")
    public static class LevelOneAdvice4 {
        private static final Metric metric = pluginServices.getMetric(LevelOneAdvice4.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@InjectTarget ExpensiveCall expensive) {
            return pluginServices.startSpan(getMessageSupplier(expensive), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }

    @Pointcut(typeName = "org.informantproject.testing.ui.ExpensiveCall", methodName = "execute5",
            metricName = "expensive 5")
    public static class LevelOneAdvice5 {
        private static final Metric metric = pluginServices.getMetric(LevelOneAdvice5.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@InjectTarget ExpensiveCall expensive) {
            return pluginServices.startSpan(getMessageSupplier(expensive), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }

    @Pointcut(typeName = "org.informantproject.testing.ui.ExpensiveCall", methodName = "execute6",
            metricName = "expensive 6")
    public static class LevelOneAdvice6 {
        private static final Metric metric = pluginServices.getMetric(LevelOneAdvice6.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@InjectTarget ExpensiveCall expensive) {
            return pluginServices.startSpan(getMessageSupplier(expensive), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }

    @Pointcut(typeName = "org.informantproject.testing.ui.ExpensiveCall", methodName = "execute7",
            metricName = "expensive 7")
    public static class LevelOneAdvice7 {
        private static final Metric metric = pluginServices.getMetric(LevelOneAdvice7.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@InjectTarget ExpensiveCall expensive) {
            return pluginServices.startSpan(getMessageSupplier(expensive), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }

    @Pointcut(typeName = "org.informantproject.testing.ui.ExpensiveCall", methodName = "execute8",
            metricName = "expensive 8")
    public static class LevelOneAdvice8 {
        private static final Metric metric = pluginServices.getMetric(LevelOneAdvice8.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@InjectTarget ExpensiveCall expensive) {
            return pluginServices.startSpan(getMessageSupplier(expensive), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }

    @Pointcut(typeName = "org.informantproject.testing.ui.ExpensiveCall", methodName = "execute9",
            metricName = "expensive 9")
    public static class LevelOneAdvice9 {
        private static final Metric metric = pluginServices.getMetric(LevelOneAdvice9.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@InjectTarget ExpensiveCall expensive) {
            return pluginServices.startSpan(getMessageSupplier(expensive), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }

    private static Supplier<Message> getMessageSupplier(ExpensiveCall expensive) {
        return MessageSupplier.of(expensive.getDescription());
    }

    private static Supplier<Message> getMessageSupplierWithContextMap(
            final ExpensiveCall expensive) {

        return new Supplier<Message>() {
            @Override
            public Message get() {
                Map<String, ?> contextMap = ImmutableMap.of("attr1", "value1", "attr2", "value2",
                        "attr3", ImmutableMap.of("attr31",
                                ImmutableMap.of("attr311", "value311", "attr312", "value312"),
                                "attr32", "value32", "attr33", "value33"));
                return Message.of(expensive.getDescription(), contextMap);
            }
        };
    }
}
