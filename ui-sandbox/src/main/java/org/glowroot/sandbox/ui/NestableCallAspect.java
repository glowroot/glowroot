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
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.Message;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.MetricName;
import org.glowroot.api.Optional;
import org.glowroot.api.PluginServices;
import org.glowroot.api.Span;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class NestableCallAspect {

    private static final ImmutableList<String> USERS = ImmutableList.of("able", "baker", "charlie");

    private static final AtomicInteger counter = new AtomicInteger();

    private static final PluginServices pluginServices = PluginServices.get("glowroot-ui-sandbox");

    @Pointcut(typeName = "org.glowroot.sandbox.ui.NestableCall", methodName = "execute",
            metricName = "nestable", ignoreSameNested = true)
    public static class NestableCallAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(NestableCallAdvice.class);
        private static final Random random = new Random();
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore() {
            int count = counter.getAndIncrement();
            String headline;
            String transactionName;
            if (random.nextBoolean()) {
                headline = "Nestable with a very long trace headline to test wrapping"
                        + " abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz";
                transactionName = "Nestable with a very long trace headline";
            } else {
                headline = Strings.repeat(String.valueOf((char) ('a' + random.nextInt(26))), 40);
                transactionName = headline;
            }
            Span span;
            if (count % 2 == 0) {
                span = pluginServices.startTrace(transactionName, getRootMessageSupplier(headline),
                        metricName);
            } else {
                span = pluginServices.startBackgroundTrace(transactionName,
                        getRootMessageSupplier(headline), metricName);
            }
            int index = count % (USERS.size() + 1);
            if (index < USERS.size()) {
                pluginServices.setTraceUser(USERS.get(index));
            } else {
                pluginServices.setTraceUser(null);
            }
            pluginServices.setTraceAttribute("my first attribute", "hello world");
            pluginServices.setTraceAttribute("and second", "val");
            pluginServices.setTraceAttribute("and a very long attribute value", "abcdefghijkl"
                    + "mnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz");
            pluginServices.setTraceAttribute("and another", "a b c d e f g h i j k l m n o p q"
                    + " r s t u v w x y z a b c d e f g h i j k l m n o p q r s t u v w x y z");
            return span;
        }
        @OnAfter
        public static void onAfter(@BindTraveler Span span) {
            double value = random.nextDouble();
            if (value < 0.8) {
                span.end();
            } else if (value < 0.9) {
                span.endWithError(ErrorMessage
                        .from("root span randomized error", new IllegalStateException()));
            } else {
                // add detail map to half of randomized errors
                span.endWithError(ErrorMessage.withDetail(
                        "root span randomized error with detail map",
                        new IllegalStateException(), ImmutableMap.of("roota", Optional.absent(),
                                "rootb", "a non-null value for rootb")));
            }
        }
    }

    private static MessageSupplier getRootMessageSupplier(final String spanText) {
        return new MessageSupplier() {
            @Override
            public Message get() {
                Map<String, ?> detail = ImmutableMap.of("attr1", getLongDetailValue(false),
                        "attr2", "value2", "attr3", ImmutableMap.of("attr31",
                                ImmutableMap.of("attr311", "value311", "attr312", "value312"),
                                "attr32", getLongDetailValue(true),
                                "attr33", getLongDetailValue(false)));
                return Message.withDetail(spanText, detail);
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
