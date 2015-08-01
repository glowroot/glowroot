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
import org.glowroot.api.PluginServices.BooleanProperty;
import org.glowroot.api.QueryEntry;
import org.glowroot.api.Timer;
import org.glowroot.api.TimerName;
import org.glowroot.api.weaving.BindReceiver;
import org.glowroot.api.weaving.BindReturn;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.Mixin;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.Pointcut;

public class ResultSetAspect {

    private static final PluginServices pluginServices = PluginServices.get("cassandra");

    @Mixin({"com.datastax.driver.core.ResultSet", "com.datastax.driver.core.ResultSetFuture"})
    public static class HasLastQueryMessageSupplierImpl implements HasLastQueryEntry {
        // the field and method names are verbose to avoid conflict since they will become fields
        // and methods in all classes that extend com.datastax.driver.core.ResultSet or
        // com.datastax.driver.core.ResultSetFuture
        //
        // does not need to be volatile, app/framework must provide visibility of ResultSets and
        // ResultSetFutures if used across threads and this can piggyback
        private @Nullable QueryEntry glowroot$lastQueryEntry;
        @Override
        @Nullable
        public QueryEntry glowroot$getLastQueryEntry() {
            return glowroot$lastQueryEntry;
        }
        @Override
        public void glowroot$setLastQueryEntry(@Nullable QueryEntry lastQueryEntry) {
            this.glowroot$lastQueryEntry = lastQueryEntry;
        }
        @Override
        public boolean glowroot$hasLastQueryEntry() {
            return glowroot$lastQueryEntry != null;
        }
    }

    // the method names are verbose to avoid conflict since they will become methods in all classes
    // that extend com.datastax.driver.core.ResultSet
    public interface HasLastQueryEntry {
        @Nullable
        QueryEntry glowroot$getLastQueryEntry();
        void glowroot$setLastQueryEntry(@Nullable QueryEntry lastQueryEntry);
        boolean glowroot$hasLastQueryEntry();
    }

    @Pointcut(className = "com.datastax.driver.core.ResultSet", methodName = "one",
            methodParameterTypes = {}, timerName = "cql resultset navigate")
    public static class OneAdvice {
        private static final TimerName timerName = pluginServices.getTimerName(OneAdvice.class);
        private static final BooleanProperty timerEnabled =
                pluginServices.getEnabledProperty("captureResultSetNavigate");
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasLastQueryEntry resultSet) {
            return resultSet.glowroot$hasLastQueryEntry();
        }
        @OnBefore
        public static @Nullable Timer onBefore() {
            if (timerEnabled.value()) {
                return pluginServices.startTimer(timerName);
            } else {
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable Object row,
                @BindReceiver HasLastQueryEntry resultSet) {
            QueryEntry lastQueryEntry = resultSet.glowroot$getLastQueryEntry();
            if (lastQueryEntry == null) {
                // tracing must be disabled (e.g. exceeded trace entry limit)
                return;
            }
            if (row != null) {
                lastQueryEntry.incrementCurrRow();
            } else {
                lastQueryEntry.setCurrRow(0);
            }
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Timer timer) {
            if (timer != null) {
                timer.stop();
            }
        }
    }
}
