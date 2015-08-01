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
package org.glowroot.plugin.cassandra;

import javax.annotation.Nullable;

import org.glowroot.api.PluginServices;
import org.glowroot.api.QueryEntry;
import org.glowroot.api.Timer;
import org.glowroot.api.TimerName;
import org.glowroot.api.weaving.BindReceiver;
import org.glowroot.api.weaving.BindReturn;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.plugin.cassandra.ResultSetAspect.HasLastQueryEntry;

public class ResultSetFutureAspect {

    private static final PluginServices pluginServices = PluginServices.get("cassandra");

    @Pointcut(className = "com.datastax.driver.core.ResultSetFuture", methodName = "get*",
            methodParameterTypes = {".."}, timerName = "cql execute async get")
    public static class GetAdvice {
        private static final TimerName timerName = pluginServices.getTimerName(GetAdvice.class);
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasLastQueryEntry resultSetFuture) {
            return resultSetFuture.glowroot$hasLastQueryEntry();
        }
        @OnBefore
        public static Timer onBefore() {
            return pluginServices.startTimer(timerName);
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable HasLastQueryEntry resultSet,
                @BindReceiver HasLastQueryEntry resultSetFuture) {
            QueryEntry lastQueryEntry = resultSetFuture.glowroot$getLastQueryEntry();
            if (resultSet != null) {
                resultSet.glowroot$setLastQueryEntry(lastQueryEntry);
            }
        }
        @OnAfter
        public static void onAfter(@BindTraveler Timer timer) {
            timer.stop();
        }
    }
}
