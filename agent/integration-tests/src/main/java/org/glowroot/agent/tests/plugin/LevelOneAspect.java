/*
 * Copyright 2011-2016 the original author or authors.
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
package org.glowroot.agent.tests.plugin;

import java.util.Map;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.Message;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.config.BooleanProperty;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.config.StringProperty;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class LevelOneAspect {

    private static final ConfigService configService =
            Agent.getConfigService("glowroot-integration-tests");

    private static final StringProperty alternateHeadline =
            configService.getStringProperty("alternateHeadline");
    private static final BooleanProperty starredHeadline =
            configService.getBooleanProperty("starredHeadline");

    @Pointcut(className = "org.glowroot.agent.tests.app.LevelOne", methodName = "call",
            methodParameterTypes = {"java.lang.Object", "java.lang.Object"},
            timerName = "level one")
    public static class LevelOneAdvice {

        private static final TimerName timerName = Agent.getTimerName(LevelOneAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(OptionalThreadContext context,
                @BindParameter final Object arg1, @BindParameter final Object arg2) {
            String headline = alternateHeadline.value();
            if (headline.isEmpty()) {
                headline = "Level One";
            }
            if (starredHeadline.value()) {
                headline += "*";
            }
            final String headlineFinal = headline;
            MessageSupplier messageSupplier = new MessageSupplier() {
                @Override
                public Message get() {
                    if (arg1.equals("useArg2AsKeyAndValue")) {
                        Map<Object, Object> map = Maps.newLinkedHashMap();
                        map.put("arg1", arg1);
                        map.put(arg2, arg2);
                        Map<Object, Object> nestedMap = Maps.newLinkedHashMap();
                        nestedMap.put("nestedkey11", arg1);
                        nestedMap.put(arg2, arg2);
                        map.put("nested1", nestedMap);
                        // intentionally doing a very bad thing here
                        @SuppressWarnings("unchecked")
                        Map<String, ?> detail = (Map<String, ?>) (Map<?, ?>) map;
                        return Message.create(headlineFinal, detail);
                    }
                    Optional<Object> optionalArg2 = Optional.fromNullable(arg2);
                    Map<String, ?> detail =
                            ImmutableMap.of("arg1", arg1, "arg2", optionalArg2, "nested1",
                                    ImmutableMap.of("nestedkey11", arg1, "nestedkey12",
                                            optionalArg2, "subnested1",
                                            ImmutableMap.of("subnestedkey1", arg1, "subnestedkey2",
                                                    optionalArg2)),
                                    "nested2", ImmutableMap.of("nestedkey21", arg1, "nestedkey22",
                                            optionalArg2));
                    return Message.create(headlineFinal, detail);
                }
            };
            TraceEntry traceEntry = context.startTransaction("Integration test", "basic test",
                    messageSupplier, timerName);
            // several trace attributes to test ordering
            context.addTransactionAttribute("Zee One", String.valueOf(arg2));
            context.addTransactionAttribute("Yee Two", "yy3");
            context.addTransactionAttribute("Yee Two", "yy");
            context.addTransactionAttribute("Yee Two", "Yy2");
            context.addTransactionAttribute("Xee Three", "xx");
            context.addTransactionAttribute("Wee Four", "ww");
            return traceEntry;
        }
        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(t);
        }
    }
}
