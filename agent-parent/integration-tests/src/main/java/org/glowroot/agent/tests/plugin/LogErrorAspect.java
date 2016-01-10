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
package org.glowroot.agent.tests.plugin;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.transaction.AdvancedService;
import org.glowroot.agent.plugin.api.transaction.MessageSupplier;
import org.glowroot.agent.plugin.api.transaction.TimerName;
import org.glowroot.agent.plugin.api.transaction.TraceEntry;
import org.glowroot.agent.plugin.api.transaction.TransactionService;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class LogErrorAspect {

    private static final TransactionService transactionService = Agent.getTransactionService();
    private static final AdvancedService advancedService = Agent.getAdvancedService();
    private static final ConfigService configService =
            Agent.getConfigService("glowroot-integration-tests");

    @Pointcut(className = "org.glowroot.agent.tests.app.LogError", methodName = "log",
            methodParameterTypes = {"java.lang.String"}, timerName = "log error")
    public static class LogErrorAdvice {

        private static final TimerName timerName =
                transactionService.getTimerName(LogErrorAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return configService.isEnabled();
        }

        @OnBefore
        public static TraceEntry onBefore(@BindParameter String message) {
            return transactionService.startTraceEntry(MessageSupplier.from("ERROR -- {}", message),
                    timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError("test error message");
        }
    }

    @Pointcut(className = "org.glowroot.agent.tests.app.LogError",
            methodName = "addNestedErrorEntry", methodParameterTypes = {},
            timerName = "add nested error entry")
    public static class AddErrorEntryAdvice {

        private static final TimerName timerName =
                transactionService.getTimerName(AddErrorEntryAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return configService.isEnabled();
        }

        @OnBefore
        public static TraceEntry onBefore() {
            TraceEntry traceEntry = transactionService.startTraceEntry(
                    MessageSupplier.from("outer entry to test nesting level"), timerName);
            advancedService.addErrorEntry("test add nested error entry message");
            return traceEntry;
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    // this is just to generate an additional $glowroot$ method to test that consecutive
    // $glowroot$ methods in an entry stack trace are stripped out correctly
    @Pointcut(className = "org.glowroot.agent.tests.app.LogError", methodName = "log",
            methodParameterTypes = {"java.lang.String"}, timerName = "log error 2")
    public static class LogErrorAdvice2 {}
}
