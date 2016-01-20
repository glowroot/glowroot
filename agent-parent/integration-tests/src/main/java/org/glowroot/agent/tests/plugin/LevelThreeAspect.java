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

import com.google.common.collect.ImmutableMap;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.transaction.Message;
import org.glowroot.agent.plugin.api.transaction.MessageSupplier;
import org.glowroot.agent.plugin.api.transaction.TimerName;
import org.glowroot.agent.plugin.api.transaction.TraceEntry;
import org.glowroot.agent.plugin.api.transaction.TransactionService;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class LevelThreeAspect {

    private static final TransactionService transactionService = Agent.getTransactionService();

    @Pointcut(className = "org.glowroot.agent.tests.app.LevelThree", methodName = "call",
            methodParameterTypes = {"java.lang.String", "java.lang.String"},
            timerName = "level three")
    public static class LevelThreeAdvice {

        private static final TimerName timerName =
                transactionService.getTimerName(LevelThreeAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(@BindParameter final String arg1,
                @BindParameter final String arg2) {
            return transactionService.startTraceEntry(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.from("Level Three", ImmutableMap.of("arg1", arg1, "arg2", arg2));
                }
            }, timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }
}
