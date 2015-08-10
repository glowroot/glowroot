/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.tests.plugin;

import java.util.Map.Entry;

import org.glowroot.plugin.api.Agent;
import org.glowroot.plugin.api.config.ConfigService;
import org.glowroot.plugin.api.transaction.MessageSupplier;
import org.glowroot.plugin.api.transaction.TimerName;
import org.glowroot.plugin.api.transaction.TraceEntry;
import org.glowroot.plugin.api.transaction.TransactionService;
import org.glowroot.plugin.api.weaving.BindReceiver;
import org.glowroot.plugin.api.weaving.BindTraveler;
import org.glowroot.plugin.api.weaving.IsEnabled;
import org.glowroot.plugin.api.weaving.OnAfter;
import org.glowroot.plugin.api.weaving.OnBefore;
import org.glowroot.plugin.api.weaving.Pointcut;
import org.glowroot.tests.TraceGeneratorBase;

public class TraceGeneratorAspect {

    private static final TransactionService transactionService = Agent.getTransactionService();
    private static final ConfigService configService =
            Agent.getConfigService("glowroot-integration-tests");

    @Pointcut(className = "org.glowroot.tests.TraceGeneratorBase", methodName = "call",
            methodParameterTypes = {"boolean"}, timerName = "trace generator")
    public static class LevelOneAdvice {

        private static final TimerName timerName =
                transactionService.getTimerName(LevelOneAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return configService.isEnabled();
        }

        @OnBefore
        public static TraceEntry onBefore(@BindReceiver TraceGeneratorBase traceGenerator) {
            TraceEntry traceEntry = transactionService.startTransaction(
                    traceGenerator.transactionType(), traceGenerator.transactionName(),
                    MessageSupplier.from(traceGenerator.headline()), timerName);
            for (Entry<String, String> entry : traceGenerator.customAttributes().entrySet()) {
                transactionService.addTransactionCustomAttribute(entry.getKey(), entry.getValue());
            }
            if (traceGenerator.error() != null) {
                transactionService.setTransactionError(traceGenerator.error());
            }
            return traceEntry;
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }
}
