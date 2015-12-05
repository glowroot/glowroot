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
package org.glowroot.agent.plugin.cassandra;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.config.BooleanProperty;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.transaction.QueryEntry;
import org.glowroot.agent.plugin.api.transaction.Timer;
import org.glowroot.agent.plugin.api.transaction.TimerName;
import org.glowroot.agent.plugin.api.transaction.TraceEntry;
import org.glowroot.agent.plugin.api.transaction.TransactionService;
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

    private static final TransactionService transactionService = Agent.getTransactionService();
    private static final ConfigService configService = Agent.getConfigService("cassandra");

    // the field and method names are verbose to avoid conflict since they will become fields
    // and methods in all classes that extend com.datastax.driver.core.ResultSet
    @Mixin("com.datastax.driver.core.ResultSet")
    public static class ResultSetImpl implements ResultSet {

        // does not need to be volatile, app/framework must provide visibility of ResultSets if used
        // across threads and this can piggyback
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

    // the field and method names are verbose to avoid conflict since they will become fields
    // and methods in all classes that extend com.datastax.driver.core.ResultSetFuture
    @Mixin("com.datastax.driver.core.ResultSetFuture")
    public static class ResultSetFutureImpl implements ResultSetFuture {

        private @Nullable QueryEntry glowroot$queryEntry;

        @Override
        @Nullable
        public QueryEntry glowroot$getQueryEntry() {
            return glowroot$queryEntry;
        }

        @Override
        public void glowroot$setQueryEntry(@Nullable QueryEntry queryEntry) {
            this.glowroot$queryEntry = queryEntry;
        }

        @Override
        public boolean glowroot$hasQueryEntry() {
            return glowroot$queryEntry != null;
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

    // the method names are verbose to avoid conflict since they will become methods in all classes
    // that extend com.datastax.driver.core.ResultSetFuture
    public interface ResultSetFuture {

        @Nullable
        QueryEntry glowroot$getQueryEntry();

        void glowroot$setQueryEntry(@Nullable QueryEntry queryEntry);

        boolean glowroot$hasQueryEntry();
    }

    @Pointcut(className = "com.datastax.driver.core.ResultSet", methodName = "one",
            methodParameterTypes = {}, timerName = "cql resultset navigate")
    public static class OneAdvice {
        private static final TimerName timerName = transactionService.getTimerName(OneAdvice.class);
        private static final BooleanProperty timerEnabled =
                configService.getEnabledProperty("captureResultSetNavigate");
        @IsEnabled
        public static boolean isEnabled(@BindReceiver ResultSet resultSet) {
            return resultSet.glowroot$hasLastQueryEntry();
        }
        @OnBefore
        public static @Nullable Timer onBefore(@BindReceiver ResultSet resultSet) {
            if (timerEnabled.value()) {
                QueryEntry lastQueryEntry = resultSet.glowroot$getLastQueryEntry();
                if (lastQueryEntry == null) {
                    // tracing must be disabled (e.g. exceeded trace entry limit)
                    return transactionService.startTimer(timerName);
                }
                return lastQueryEntry.extend();
            } else {
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable Object row,
                @BindReceiver ResultSet resultSet) {
            QueryEntry lastQueryEntry = resultSet.glowroot$getLastQueryEntry();
            if (lastQueryEntry == null) {
                // tracing must be disabled (e.g. exceeded trace entry limit)
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

    @Pointcut(className = "com.datastax.driver.core.ResultSetFuture",
            declaringClassName = "java.util.concurrent.Future", methodName = "get",
            methodParameterTypes = {".."})
    public static class FutureGetAdvice {
        @OnBefore
        public static @Nullable Timer onBefore(@BindReceiver ResultSetFuture resultSetFuture) {
            TraceEntry traceEntry = resultSetFuture.glowroot$getQueryEntry();
            if (traceEntry != null) {
                return traceEntry.extend();
            }
            return null;
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable ResultSet resultSet,
                @BindReceiver ResultSetFuture resultSetFuture) {
            QueryEntry queryEntry = resultSetFuture.glowroot$getQueryEntry();
            if (resultSet != null) {
                // pass the query entry to the return value so it can be used when iterating over
                // the result set
                resultSet.glowroot$setLastQueryEntry(queryEntry);
            }
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Timer timer) {
            if (timer != null) {
                timer.stop();
            }
        }
    }

    @Pointcut(className = "com.datastax.driver.core.ResultSetFuture",
            methodName = "getUninterruptibly", methodParameterTypes = {".."})
    public static class GetAdvice {
        @OnReturn
        public static void onReturn(@BindReturn @Nullable ResultSet resultSet,
                @BindReceiver ResultSetFuture resultSetFuture) {
            QueryEntry lastQueryEntry = resultSetFuture.glowroot$getQueryEntry();
            if (resultSet != null) {
                // pass the query entry to the return value so it can be used when iterating over
                // the result set
                resultSet.glowroot$setLastQueryEntry(lastQueryEntry);
            }
        }
    }
}
