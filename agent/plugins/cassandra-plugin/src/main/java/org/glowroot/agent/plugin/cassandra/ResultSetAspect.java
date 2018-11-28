/*
 * Copyright 2015-2018 the original author or authors.
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

import org.glowroot.agent.plugin.api.QueryEntry;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class ResultSetAspect {

    // the field and method names are verbose since they will be mixed in to existing classes
    @Mixin("com.datastax.driver.core.ResultSet")
    public static class ResultSetImpl implements ResultSetMixin {

        // this may be async or non-async query entry
        //
        // needs to be volatile, since ResultSets are thread safe, and therefore app/framework does
        // *not* need to provide visibility when used across threads and so this cannot piggyback
        // (unlike with jdbc ResultSets)
        private transient volatile @Nullable QueryEntry glowroot$queryEntry;

        @Override
        public @Nullable QueryEntry glowroot$getQueryEntry() {
            return glowroot$queryEntry;
        }

        @Override
        public void glowroot$setQueryEntry(@Nullable QueryEntry queryEntry) {
            glowroot$queryEntry = queryEntry;
        }
    }

    // the method names are verbose since they will be mixed in to existing classes
    public interface ResultSetMixin {

        @Nullable
        QueryEntry glowroot$getQueryEntry();

        void glowroot$setQueryEntry(@Nullable QueryEntry queryEntry);
    }

    @Pointcut(className = "com.datastax.driver.core.ResultSet", methodName = "one",
            methodParameterTypes = {})
    public static class OneAdvice {

        @OnBefore
        public static @Nullable Timer onBefore(@BindReceiver ResultSetMixin resultSet) {
            QueryEntry queryEntry = resultSet.glowroot$getQueryEntry();
            return queryEntry == null ? null : queryEntry.extend();
        }

        @OnReturn
        public static void onReturn(@BindReturn @Nullable Object row,
                @BindReceiver ResultSetMixin resultSet) {
            QueryEntry queryEntry = resultSet.glowroot$getQueryEntry();
            if (queryEntry == null) {
                return;
            }
            if (row != null) {
                queryEntry.incrementCurrRow();
            } else {
                queryEntry.rowNavigationAttempted();
            }
        }

        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Timer timer) {
            if (timer != null) {
                timer.stop();
            }
        }
    }

    @Pointcut(className = "java.lang.Iterable",
            subTypeRestriction = "com.datastax.driver.core.ResultSet",
            methodName = "iterator", methodParameterTypes = {})
    public static class IteratorAdvice {

        @OnReturn
        public static void onReturn(@BindReceiver ResultSetMixin resultSet) {
            QueryEntry queryEntry = resultSet.glowroot$getQueryEntry();
            if (queryEntry == null) {
                // tracing must be disabled (e.g. exceeded trace entry limit)
                return;
            }
            queryEntry.rowNavigationAttempted();
        }
    }

    @Pointcut(className = "com.datastax.driver.core.PagingIterable"
            + "|com.datastax.driver.core.ResultSet",
            subTypeRestriction = "com.datastax.driver.core.ResultSet",
            methodName = "isExhausted", methodParameterTypes = {})
    public static class IsExhaustedAdvice {

        @OnBefore
        public static @Nullable Timer onBefore(@BindReceiver ResultSetMixin resultSet) {
            QueryEntry queryEntry = resultSet.glowroot$getQueryEntry();
            return queryEntry == null ? null : queryEntry.extend();
        }

        @OnReturn
        public static void onReturn(@BindReceiver ResultSetMixin resultSet) {
            QueryEntry queryEntry = resultSet.glowroot$getQueryEntry();
            if (queryEntry == null) {
                // tracing must be disabled (e.g. exceeded trace entry limit)
                return;
            }
            queryEntry.rowNavigationAttempted();
        }

        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Timer timer) {
            if (timer != null) {
                timer.stop();
            }
        }
    }
}
