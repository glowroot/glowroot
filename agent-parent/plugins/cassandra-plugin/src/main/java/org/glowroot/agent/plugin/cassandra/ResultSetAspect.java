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
package org.glowroot.agent.plugin.cassandra;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.config.BooleanProperty;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.transaction.QueryEntry;
import org.glowroot.agent.plugin.api.transaction.Timer;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class ResultSetAspect {

    private static final ConfigService configService = Agent.getConfigService("cassandra");

    // the field and method names are verbose to avoid conflict since they will become fields
    // and methods in all classes that extend com.datastax.driver.core.ResultSet
    @Mixin("com.datastax.driver.core.ResultSet")
    public static class ResultSetImpl implements ResultSet {

        // this may be async or non-async query entry
        //
        // does not need to be volatile, app/framework must provide visibility of ResultSets if used
        // across threads and this can piggyback
        private @Nullable QueryEntry glowroot$lastQueryEntry;

        // this is always null for sync queries, and always non-null for async queries
        private @Nullable Timer glowroot$lastNonAsyncTimer;

        @Override
        public @Nullable QueryEntry glowroot$getLastQueryEntry() {
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
    public interface ResultSet {

        @Nullable
        QueryEntry glowroot$getLastQueryEntry();

        void glowroot$setLastQueryEntry(@Nullable QueryEntry lastQueryEntry);

        boolean glowroot$hasLastQueryEntry();
    }

    @Pointcut(className = "com.datastax.driver.core.ResultSet", methodName = "one",
            methodParameterTypes = {})
    public static class OneAdvice {
        private static final BooleanProperty timerEnabled =
                configService.getBooleanProperty("captureResultSetNavigate");
        @IsEnabled
        public static boolean isEnabled(@BindReceiver ResultSet resultSet) {
            return resultSet.glowroot$hasLastQueryEntry();
        }
        @OnBefore
        public static @Nullable Timer onBefore(@BindReceiver ResultSet resultSet) {
            if (!timerEnabled.value()) {
                return null;
            }
            QueryEntry lastQueryEntry = resultSet.glowroot$getLastQueryEntry();
            if (lastQueryEntry == null) {
                return null;
            }
            return lastQueryEntry.extend();
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable Object row,
                @BindReceiver ResultSet resultSet) {
            QueryEntry lastQueryEntry = resultSet.glowroot$getLastQueryEntry();
            if (lastQueryEntry == null) {
                return;
            }
            if (row != null) {
                lastQueryEntry.incrementCurrRow();
            } else {
                lastQueryEntry.rowNavigationAttempted();
            }
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Timer timer) {
            if (timer != null) {
                timer.stop();
            }
        }
    }

    @Pointcut(className = "com.datastax.driver.core.ResultSet", methodName = "iterator",
            methodParameterTypes = {})
    public static class IteratorAdvice {
        @OnReturn
        public static void onReturn(@BindReceiver ResultSet resultSet) {
            QueryEntry lastQueryEntry = resultSet.glowroot$getLastQueryEntry();
            if (lastQueryEntry == null) {
                // tracing must be disabled (e.g. exceeded trace entry limit)
                return;
            }
            lastQueryEntry.rowNavigationAttempted();
        }
    }
}
