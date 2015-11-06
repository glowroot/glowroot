/*
 * Copyright 2012-2015 the original author or authors.
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
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.transaction.Message;
import org.glowroot.agent.plugin.api.transaction.MessageSupplier;
import org.glowroot.agent.plugin.api.transaction.TimerName;
import org.glowroot.agent.plugin.api.transaction.TraceEntry;
import org.glowroot.agent.plugin.api.transaction.TransactionService;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class NestableCallAspect {

    private static final ImmutableList<String> USERS = ImmutableList.of("able", "baker", "charlie");

    private static final AtomicInteger counter = new AtomicInteger();

    private static final TransactionService transactionService = Agent.getTransactionService();
    private static final ConfigService configService =
            Agent.getConfigService("glowroot-ui-sandbox");

    @Pointcut(className = "org.glowroot.agent.ui.sandbox.NestableCall", methodName = "execute",
            methodParameterTypes = {}, timerName = "nestable", ignoreSelfNested = true)
    public static class NestableCallAdvice {
        private static final TimerName timerName =
                transactionService.getTimerName(NestableCallAdvice.class);
        private static final Random random = new Random();
        @IsEnabled
        public static boolean isEnabled() {
            return configService.isEnabled();
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
                traceEntry = transactionService.startTransaction("Background", transactionName,
                        getRootMessageSupplier(headline), timerName);
            } else {
                traceEntry = transactionService.startTransaction("Sandbox", transactionName,
                        getRootMessageSupplier(headline), timerName);
            }
            int index = count % (USERS.size() + 1);
            if (index < USERS.size()) {
                transactionService.setTransactionUser(USERS.get(index));
            } else {
                transactionService.setTransactionUser(null);
            }
            if (random.nextBoolean()) {
                transactionService.addTransactionAttribute("My First Attribute", "hello world");
                transactionService.addTransactionAttribute("My First Attribute", "hello world");
                transactionService.addTransactionAttribute("My First Attribute",
                        "hello world " + random.nextInt(10));
            }
            if (random.nextBoolean()) {
                transactionService.addTransactionAttribute("Second", "val " + random.nextInt(10));
            }
            if (random.nextBoolean()) {
                transactionService.addTransactionAttribute("A Very Long Attribute Value",
                        Strings.repeat("abcdefghijklmnopqrstuvwxyz", 3));
            }
            if (random.nextBoolean()) {
                transactionService.addTransactionAttribute("Another",
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
            } else if (value < 0.9) {
                traceEntry.endWithError("root entry randomized error", new IllegalStateException());
            } else {
                String reallyLongErrorMessage = Strings.repeat("abcdefghijklmnopqrstuvwxyz ", 100);
                traceEntry.endWithError(reallyLongErrorMessage, new IllegalStateException());
            }
        }
    }

    private static MessageSupplier getRootMessageSupplier(final String traceEntryMessage) {
        return new MessageSupplier() {
            @Override
            public Message get() {
                Map<String, ?> detail = ImmutableMap
                        .of("attr1", getLongDetailValue(false), "attr2", "value2", "attr3",
                                ImmutableMap.of("attr31",
                                        ImmutableMap.of("attr311",
                                                ImmutableList.of("v311a", "v311b")),
                                        "attr32", getLongDetailValue(true), "attr33",
                                        getLongDetailValue(false)));
                return Message.from(traceEntryMessage, detail);
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
