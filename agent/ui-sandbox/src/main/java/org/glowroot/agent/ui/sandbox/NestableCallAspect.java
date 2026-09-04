/*
 * Copyright 2012-2016 the original author or authors.
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
import org.glowroot.agent.plugin.api.Message;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext.Priority;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class NestableCallAspect {

    private static final ImmutableList<String> USERS = ImmutableList.of("able", "baker", "charlie");

    private static final AtomicInteger counter = new AtomicInteger();

    @Pointcut(className = "org.glowroot.agent.ui.sandbox.NestableCall", methodName = "execute",
            methodParameterTypes = {}, nestingGroup = "ui-sandbox-nestable", timerName = "nestable")
    public static class NestableCallAdvice {
        private static final TimerName timerName = Agent.getTimerName(NestableCallAdvice.class);
        private static final Random random = new Random();
        @OnBefore
        public static TraceEntry onBefore(OptionalThreadContext context) {
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
                traceEntry = context.startTransaction("Background", transactionName,
                        getRootMessageSupplier(headline), timerName);
            } else {
                traceEntry = context.startTransaction("Sandbox", transactionName,
                        getRootMessageSupplier(headline), timerName);
            }
            int index = count % (USERS.size() + 1);
            if (index < USERS.size()) {
                context.setTransactionUser(USERS.get(index), Priority.USER_PLUGIN);
            } else {
                context.setTransactionUser(null, Priority.USER_PLUGIN);
            }
            if (random.nextBoolean()) {
                context.addTransactionAttribute("My First Attribute", "hello world");
                context.addTransactionAttribute("My First Attribute", "hello world");
                context.addTransactionAttribute("My First Attribute",
                        "hello world " + random.nextInt(10));
            }
            if (random.nextBoolean()) {
                context.addTransactionAttribute("Second", "val " + random.nextInt(10));
            }
            if (random.nextBoolean()) {
                context.addTransactionAttribute("A Very Long Attribute Value",
                        Strings.repeat("abcdefghijklmnopqrstuvwxyz", 3));
            }
            if (random.nextBoolean()) {
                context.addTransactionAttribute("Another",
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
                return Message.create(traceEntryMessage, detail);
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
