/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.plugin.servlet;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.transaction.MessageSupplier;
import org.glowroot.agent.plugin.api.transaction.TimerName;
import org.glowroot.agent.plugin.api.transaction.TraceEntry;
import org.glowroot.agent.plugin.api.transaction.TransactionService;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

// this covers Tomcat, TomEE, Glassfish, JBoss EAP
public class CatalinaAppStartupAspect {

    private static final TransactionService transactionService = Agent.getTransactionService();

    @Shim("org.apache.catalina.core.StandardContext")
    public interface StandardContext {
        @Nullable
        String getPath();
    }

    // startInternal is needed for Tomcat 7+ which moved the start() method up into a new super
    // class, org.apache.catalina.util.LifecycleBase, but this new start() method delegates to
    // abstract method startInternal() which does all of the real work
    @Pointcut(className = "org.apache.catalina.core.StandardContext",
            methodName = "start|startInternal", methodParameterTypes = {},
            timerName = "http request")
    public static class StartAdvice {
        private static final TimerName timerName =
                transactionService.getTimerName(StartAdvice.class);
        @OnBefore
        public static TraceEntry onBefore(@BindReceiver StandardContext standardContext) {
            String path = standardContext.getPath();
            String transactionName;
            if (path == null || path.isEmpty()) {
                // root context path is empty "", but makes more sense to display "/"
                transactionName = "Servlet context: /";
            } else {
                transactionName = "Servlet context: " + path;
            }
            return transactionService.startTransaction("Startup", transactionName,
                    MessageSupplier.from(transactionName), timerName);
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
