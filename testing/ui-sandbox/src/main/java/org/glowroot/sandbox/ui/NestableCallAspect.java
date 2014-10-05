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
import org.glowroot.api.TraceEntry;
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

    @Pointcut(className = "org.glowroot.sandbox.ui.NestableCall", methodName = "execute",
            metricName = "nestable", ignoreSelfNested = true)
    public static class NestableCallAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(NestableCallAdvice.class);
        private static final Random random = new Random();
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static TraceEntry onBefore() {
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
            TraceEntry traceEntry;
            if (count % 10 == 0) {
                traceEntry = pluginServices.startTransaction("Background", transactionName,
                        getRootMessageSupplier(headline), metricName);
            } else {
                traceEntry = pluginServices.startTransaction("Sandbox", transactionName,
                        getRootMessageSupplier(headline), metricName);
            }
            int index = count % (USERS.size() + 1);
            if (index < USERS.size()) {
                pluginServices.setTransactionUser(USERS.get(index));
            } else {
                pluginServices.setTransactionUser(null);
            }
            if (random.nextBoolean()) {
                pluginServices.setTransactionCustomAttribute("My First Attribute", "hello world");
                pluginServices.setTransactionCustomAttribute("My First Attribute", "hello world");
                pluginServices.setTransactionCustomAttribute("My First Attribute",
                        "hello world " + random.nextInt(10));
            }
            if (random.nextBoolean()) {
                pluginServices.setTransactionCustomAttribute("Second", "val " + random.nextInt(10));
            }
            if (random.nextBoolean()) {
                pluginServices.setTransactionCustomAttribute("A Very Long Attribute Value",
                        Strings.repeat("abcdefghijklmnopqrstuvwxyz", 3));
            }
            if (random.nextBoolean()) {
                pluginServices.setTransactionCustomAttribute("Another",
                        "a b c d e f g h i j k l m n o p q r s t u v w x y z"
                                + " a b c d e f g h i j k l m n o p q r s t u v w x y z");
            }
            return traceEntry;
        }
        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            double value = random.nextDouble();
            if (value < 0.8) {
                traceEntry.end();
            } else if (value < 0.89) {
                traceEntry.endWithError(ErrorMessage.from("root entry randomized error",
                        new IllegalStateException()));
            } else if (value < 0.98) {
                // add detail map to half of randomized errors
                traceEntry.endWithError(ErrorMessage.withDetail(
                        "root entry randomized error with detail map",
                        new IllegalStateException(), ImmutableMap.of("roota", Optional.absent(),
                                "rootb", "a non-null value for rootb")));
            } else {
                String reallyLongErrorMessage = Strings.repeat("abcdefghijklmnopqrstuvwxyz ", 100);
                traceEntry.endWithError(ErrorMessage.from(reallyLongErrorMessage,
                        new IllegalStateException()));
            }
        }
    }

    private static MessageSupplier getRootMessageSupplier(final String traceEntryMessage) {
        return new MessageSupplier() {
            @Override
            public Message get() {
                Map<String, ?> detail = ImmutableMap.of("attr1", getLongDetailValue(false),
                        "attr2", "value2", "attr3", ImmutableMap.of("attr31",
                                ImmutableMap.of("attr311", ImmutableList.of("v311a", "v311b")),
                                "attr32", getLongDetailValue(true),
                                "attr33", getLongDetailValue(false)));
                return Message.withDetail(traceEntryMessage, detail);
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
