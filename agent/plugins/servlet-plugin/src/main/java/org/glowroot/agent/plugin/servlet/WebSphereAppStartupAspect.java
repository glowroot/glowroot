/*
 * Copyright 2017-2023 the original author or authors.
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

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

public class WebSphereAppStartupAspect {

    @Shim("com.ibm.ws.webcontainer.webapp.WebApp")
    public interface WebAppShim {
        @Nullable
        String getContextPath();
    }

    @Pointcut(className = "com.ibm.ws.webcontainer.webapp.WebApp",
            methodName = "commonInitializationFinally|commonInitializationFinish",
            methodParameterTypes = {"java.util.List"}, nestingGroup = "servlet-startup",
            timerName = "startup")
    public static class StartAdvice {
        private static final TimerName timerName = Agent.getTimerName(StartAdvice.class);
        @OnBefore
        public static TraceEntry onBefore(OptionalThreadContext context,
                @BindReceiver WebAppShim webApp) {
            String path = webApp.getContextPath();
            return ContainerStartup.onBeforeCommon(context, path, timerName);
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
