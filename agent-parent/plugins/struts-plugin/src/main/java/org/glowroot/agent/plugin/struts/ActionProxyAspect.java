/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.plugin.struts;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.transaction.MessageSupplier;
import org.glowroot.agent.plugin.api.transaction.TimerName;
import org.glowroot.agent.plugin.api.transaction.TraceEntry;
import org.glowroot.agent.plugin.api.transaction.TransactionService;
import org.glowroot.agent.plugin.api.weaving.*;

public class ActionProxyAspect {

    private static final TransactionService transactionService = Agent.getTransactionService();
    private static final ConfigService configService = Agent.getConfigService("struts");

    @Shim("com.opensymphony.xwork2.ActionProxy")
    public interface ActionProxy {
        Object getAction();

        String getMethod();
    }

    @Pointcut(className = "com.opensymphony.xwork2.ActionProxy",
            methodName = "execute",
            methodParameterTypes = {}, timerName = "struts controller")
    public static class ActionProxyAdvice {
        private static final TimerName timerName = transactionService.getTimerName(ActionProxyAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return configService.isEnabled();
        }

        @OnBefore
        public static TraceEntry onBefore(@BindReceiver ActionProxy actionProxy) {
            String actionClass = actionProxy.getAction().getClass().getSimpleName();
            String methodName = actionProxy.getMethod() != null ? actionProxy.getMethod() : "execute";
            transactionService.setTransactionName(actionClass + "#" + methodName);
            return transactionService.startTraceEntry(MessageSupplier.from("struts action: {}#{}", actionClass, methodName), timerName);
        }

        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable throwable, @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(throwable);
        }
    }

    @Shim("org.apache.struts.action.Action")
    public interface Action {
    }

    @Pointcut(className = "org.apache.struts.action.Action",
            methodName = "execute",
            methodParameterTypes = {
                    "org.apache.struts.action.ActionMapping",
                    "org.apache.struts.action.ActionForm",
                    ".."
            }, timerName = "struts controller")
    public static class ActionAdvice {
        private static final TimerName timerName = transactionService.getTimerName(ActionProxyAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return configService.isEnabled();
        }

        @OnBefore
        public static TraceEntry onBefore(@BindReceiver Action action) {
            String actionClass = action.getClass().getSimpleName();
            String methodName = "execute";
            transactionService.setTransactionName(actionClass + "#" + methodName);
            return transactionService.startTraceEntry(MessageSupplier.from("struts action: {}#{}", actionClass, methodName), timerName);
        }

        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable throwable, @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(throwable);
        }
    }
}
