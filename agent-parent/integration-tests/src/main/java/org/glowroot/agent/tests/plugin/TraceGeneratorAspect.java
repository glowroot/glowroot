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
package org.glowroot.agent.tests.plugin;

import java.util.Map;
import java.util.Map.Entry;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.transaction.MessageSupplier;
import org.glowroot.agent.plugin.api.transaction.TimerName;
import org.glowroot.agent.plugin.api.transaction.TraceEntry;
import org.glowroot.agent.plugin.api.transaction.TransactionService;
import org.glowroot.agent.plugin.api.weaving.BindClassMeta;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class TraceGeneratorAspect {

    private static final TransactionService transactionService = Agent.getTransactionService();
    private static final ConfigService configService =
            Agent.getConfigService("glowroot-integration-tests");

    @Pointcut(className = "org.glowroot.agent.tests.app.TraceGenerator", methodName = "call",
            methodParameterTypes = {"boolean"}, timerName = "trace generator")
    public static class LevelOneAdvice {

        private static final TimerName timerName =
                transactionService.getTimerName(LevelOneAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return configService.isEnabled();
        }

        @OnBefore
        public static TraceEntry onBefore(@BindReceiver Object traceGenerator,
                @BindClassMeta TraceGeneratorInvoker traceGeneratorInvoker) {
            String transactionType = traceGeneratorInvoker.transactionType(traceGenerator);
            String transactionName = traceGeneratorInvoker.transactionName(traceGenerator);
            String headline = traceGeneratorInvoker.headline(traceGenerator);
            Map<String, String> attributes = traceGeneratorInvoker.attributes(traceGenerator);
            String error = traceGeneratorInvoker.error(traceGenerator);

            TraceEntry traceEntry =
                    transactionService.startTransaction(transactionType, transactionName,
                            MessageSupplier.from(headline), timerName);
            if (attributes != null) {
                for (Entry<String, String> entry : attributes.entrySet()) {
                    transactionService.addTransactionAttribute(entry.getKey(), entry.getValue());
                }
            }
            if (error != null) {
                transactionService.setTransactionError(error);
            }
            return traceEntry;
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }
}
