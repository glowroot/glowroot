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
import org.glowroot.api.Optional;
import org.glowroot.api.PluginServices;
import org.glowroot.api.Span;
import org.glowroot.api.TraceMetricName;
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

    @Pointcut(type = "org.glowroot.sandbox.ui.NestableCall", methodName = "execute",
            traceMetric = "nestable", ignoreSameNested = true)
    public static class NestableCallAdvice {
        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(NestableCallAdvice.class);
        private static final Random random = new Random();
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore() {
            int count = counter.getAndIncrement();
            String transactionName;
            String headline;
            if (random.nextBoolean()) {
                transactionName = "Nestable with a very long trace headline";
                headline = "Nestable with a very long trace headline to test wrapping"
                        + " abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz";
            } else {
                transactionName =
                        Strings.repeat(String.valueOf((char) ('a' + random.nextInt(26))), 40);
                headline = transactionName;
            }
            if (random.nextInt(10) == 0) {
                // create a long-tail of transaction names to simulate long-tail of urls
                transactionName += random.nextInt(1000);
            }
            Span span;
            if (count % 10 == 0) {
                span = pluginServices.startBackgroundTrace(transactionName,
                        getRootMessageSupplier(headline), traceMetricName);
            } else {
                span = pluginServices.startTrace(transactionName, getRootMessageSupplier(headline),
                        traceMetricName);
            }
            int index = count % (USERS.size() + 1);
            if (index < USERS.size()) {
                pluginServices.setTraceUser(USERS.get(index));
            } else {
                pluginServices.setTraceUser(null);
            }
            if (random.nextBoolean()) {
                pluginServices.addTraceAttribute("My First Attribute", "hello world");
                pluginServices.addTraceAttribute("My First Attribute", "hello world");
                pluginServices.addTraceAttribute("My First Attribute",
                        "hello world " + random.nextInt(10));
            }
            if (random.nextBoolean()) {
                pluginServices.addTraceAttribute("Second", "val " + random.nextInt(10));
            }
            if (random.nextBoolean()) {
                pluginServices.addTraceAttribute("A Very Long Attribute Value", "abcdefghijkl"
                        + "mnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz");
            }
            if (random.nextBoolean()) {
                pluginServices.addTraceAttribute("Another", "a b c d e f g h i j k l m n o p q"
                        + " r s t u v w x y z a b c d e f g h i j k l m n o p q r s t u v w x y z");
            }
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
                                ImmutableMap.of("attr311", ImmutableList.of("v311a", "v311b")),
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
