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

import org.glowroot.agent.plugin.api.QueryEntry;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class ResultSetAspect {

    // the field and method names are verbose to avoid conflict since they will become fields
    // and methods in all classes that extend com.datastax.driver.core.ResultSet
    @Mixin("com.datastax.driver.core.ResultSet")
    public static class ResultSetImpl implements ResultSet {

        // this may be async or non-async query entry
        //
        // does not need to be volatile, app/framework must provide visibility of ResultSets if used
        // across threads and this can piggyback
        private @Nullable QueryEntry glowroot$lastQueryEntry;

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
    }

    @Pointcut(className = "com.datastax.driver.core.ResultSet",
            methodDeclaringClassName = "java.lang.Iterable", methodName = "iterator",
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

    @Pointcut(className = "com.datastax.driver.core.ResultSet",
            methodDeclaringClassName = "com.datastax.driver.core.PagingIterable"
                    + "|com.datastax.driver.core.ResultSet",
            methodName = "isExhausted", methodParameterTypes = {})
    public static class IsExhaustedAdvice {
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
