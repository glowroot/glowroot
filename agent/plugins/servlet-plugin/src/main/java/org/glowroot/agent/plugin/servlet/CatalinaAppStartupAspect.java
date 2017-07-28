/*
 * Copyright 2015-2017 the original author or authors.
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
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
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
            nestingGroup = "servlet-startup", timerName = "startup")
    public static class StartAdvice {
        private static final TimerName timerName = Agent.getTimerName(StartAdvice.class);
        @OnBefore
        public static TraceEntry onBefore(OptionalThreadContext context,
                @BindReceiver StandardContext standardContext) {
            String path = standardContext.getPath();
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
