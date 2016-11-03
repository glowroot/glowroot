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
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class LogCauseAspect {

    @Pointcut(className = "org.glowroot.agent.tests.app.LogCause", methodName = "log",
            methodParameterTypes = {"java.lang.String"}, timerName = "log error")
    public static class LogCauseAdvice {

        private static final TimerName timerName = Agent.getTimerName(LogCauseAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context, @BindParameter String message) {
            return context.startTraceEntry(MessageSupplier.create("ERROR -- {}", message),
                    timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            Exception cause1 = new NullPointerException("Cause 1");
            Exception cause2 = new IllegalStateException("Cause 2", cause1);
            Exception cause3 = new IllegalArgumentException("Cause 3", cause2);
            traceEntry.endWithError(new IllegalStateException(cause3));
        }
    }
}
