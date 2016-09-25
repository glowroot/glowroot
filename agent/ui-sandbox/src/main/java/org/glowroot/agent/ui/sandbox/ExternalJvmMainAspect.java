/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.agent.ui.sandbox;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

// this is used to generate a trace with <multiple root nodes> (and with multiple timers) just to
// test this unusual situation
public class ExternalJvmMainAspect {

    @Pointcut(className = "org.glowroot.agent.it.harness.impl.JavaagentMain", methodName = "main",
            methodParameterTypes = {"java.lang.String[]"}, timerName = "external jvm main")
    public static class MainAdvice {

        private static final TimerName timerName = Agent.getTimerName(MainAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(OptionalThreadContext context) {
            return context.startTransaction("Sandbox", "javaagent container main",
                    MessageSupplier
                            .create("org.glowroot.agent.it.harness.impl.JavaagentMain.main()"),
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

        private static final TimerName timerName = Agent.getTimerName(TimerMarkerOneAdvice.class);

        @OnBefore
        public static Timer onBefore(ThreadContext context) {
            return context.startTimer(timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler Timer timer) {
            timer.stop();
        }
    }

    @Pointcut(className = "org.glowroot.agent.it.harness.impl.JavaagentMain",
            methodName = "timerMarkerTwo", methodParameterTypes = {}, timerName = "timer two")
    public static class TimerMarkerTwoAdvice {

        private static final TimerName timerName = Agent.getTimerName(TimerMarkerTwoAdvice.class);

        @OnBefore
        public static Timer onBefore(ThreadContext context) {
            return context.startTimer(timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler Timer timer) {
            timer.stop();
        }
    }
}
