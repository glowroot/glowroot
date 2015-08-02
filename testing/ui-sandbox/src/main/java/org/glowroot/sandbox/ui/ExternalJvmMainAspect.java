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
package org.glowroot.sandbox.ui;

import org.glowroot.plugin.api.MessageSupplier;
import org.glowroot.plugin.api.PluginServices;
import org.glowroot.plugin.api.Timer;
import org.glowroot.plugin.api.TimerName;
import org.glowroot.plugin.api.TraceEntry;
import org.glowroot.plugin.api.weaving.BindTraveler;
import org.glowroot.plugin.api.weaving.IsEnabled;
import org.glowroot.plugin.api.weaving.OnAfter;
import org.glowroot.plugin.api.weaving.OnBefore;
import org.glowroot.plugin.api.weaving.Pointcut;

// this is used to generate a trace with <multiple root nodes> (and with multiple timers) just to
// test this unusual situation
public class ExternalJvmMainAspect {

    private static final PluginServices pluginServices = PluginServices.get("glowroot-ui-sandbox");

    @Pointcut(className = "org.glowroot.container.impl.JavaagentMain",
            methodName = "main", methodParameterTypes = {"java.lang.String[]"},
            timerName = "external jvm main")
    public static class MainAdvice {

        private static final TimerName timerName =
                pluginServices.getTimerName(MainAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static TraceEntry onBefore() {
            return pluginServices.startTransaction("Sandbox", "javaagent container main",
                    MessageSupplier.from("org.glowroot.container.impl.JavaagentMain"
                            + ".main()"),
                    timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    @Pointcut(className = "org.glowroot.container.impl.JavaagentMain",
            methodName = "timerMarkerOne", methodParameterTypes = {}, timerName = "timer one")
    public static class TimerMarkerOneAdvice {

        private static final TimerName timerName =
                pluginServices.getTimerName(TimerMarkerOneAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static Timer onBefore() {
            return pluginServices.startTimer(timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler Timer timer) {
            timer.stop();
        }
    }

    @Pointcut(className = "org.glowroot.container.impl.JavaagentMain",
            methodName = "timerMarkerTwo", methodParameterTypes = {}, timerName = "timer two")
    public static class TimerMarkerTwoAdvice {

        private static final TimerName timerName =
                pluginServices.getTimerName(TimerMarkerTwoAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static Timer onBefore() {
            return pluginServices.startTimer(timerName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler Timer timer) {
            timer.stop();
        }
    }
}
