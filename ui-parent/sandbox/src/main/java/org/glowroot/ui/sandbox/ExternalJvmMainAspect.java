/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.ui.sandbox;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.transaction.MessageSupplier;
import org.glowroot.agent.plugin.api.transaction.Timer;
import org.glowroot.agent.plugin.api.transaction.TimerName;
import org.glowroot.agent.plugin.api.transaction.TraceEntry;
import org.glowroot.agent.plugin.api.transaction.TransactionService;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

// this is used to generate a trace with <multiple root nodes> (and with multiple timers) just to
// test this unusual situation
public class ExternalJvmMainAspect {

    private static final TransactionService transactionService = Agent.getTransactionService();
    private static final ConfigService configService =
            Agent.getConfigService("glowroot-ui-sandbox");

    @Pointcut(className = "org.glowroot.agent.it.harness.impl.JavaagentMain", methodName = "main",
            methodParameterTypes = {"java.lang.String[]"}, timerName = "external jvm main")
    public static class MainAdvice {

        private static final TimerName timerName =
                transactionService.getTimerName(MainAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return configService.isEnabled();
        }

        @OnBefore
        public static TraceEntry onBefore() {
            return transactionService.startTransaction("Sandbox", "javaagent container main",
                    MessageSupplier.from("org.glowroot.agent.it.harness.impl.JavaagentMain.main()"),
                    timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    @Pointcut(className = "org.glowroot.agent.it.harness.impl.JavaagentMain",
            methodName = "timerMarkerOne", methodParameterTypes = {}, timerName = "timer one")
    public static class TimerMarkerOneAdvice {

        private static final TimerName timerName =
                transactionService.getTimerName(TimerMarkerOneAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return configService.isEnabled();
        }

        @OnBefore
        public static Timer onBefore() {
            return transactionService.startTimer(timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler Timer timer) {
            timer.stop();
        }
    }

    @Pointcut(className = "org.glowroot.agent.it.harness.impl.JavaagentMain",
            methodName = "timerMarkerTwo", methodParameterTypes = {}, timerName = "timer two")
    public static class TimerMarkerTwoAdvice {

        private static final TimerName timerName =
                transactionService.getTimerName(TimerMarkerTwoAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return configService.isEnabled();
        }

        @OnBefore
        public static Timer onBefore() {
            return transactionService.startTimer(timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler Timer timer) {
            timer.stop();
        }
    }
}
