/*
 * Copyright 2016 the original author or authors.
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

import org.glowroot.agent.plugin.api.transaction.AsyncQueryEntry;
import org.glowroot.agent.plugin.api.transaction.Timer;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.cassandra.ResultSetAspect.ResultSet;

public class ResultSetFutureAspect {

    // the field and method names are verbose to avoid conflict since they will become fields
    // and methods in all classes that extend com.datastax.driver.core.ResultSetFuture
    @Mixin("com.datastax.driver.core.ResultSetFuture")
    public static class ResultSetFutureImpl implements ResultSetFutureMixin {

        private @Nullable AsyncQueryEntry glowroot$asyncQueryEntry;

        @Override
        public @Nullable AsyncQueryEntry glowroot$getAsyncQueryEntry() {
            return glowroot$asyncQueryEntry;
        }

        @Override
        public void glowroot$setAsyncQueryEntry(@Nullable AsyncQueryEntry asyncQueryEntry) {
            this.glowroot$asyncQueryEntry = asyncQueryEntry;
        }

        @Override
        public boolean glowroot$hasAsyncQueryEntry() {
            return glowroot$asyncQueryEntry != null;
        }
    }

    // the method names are verbose to avoid conflict since they will become methods in all classes
    // that extend com.datastax.driver.core.ResultSetFuture
    public interface ResultSetFutureMixin {

        @Nullable
        AsyncQueryEntry glowroot$getAsyncQueryEntry();

        void glowroot$setAsyncQueryEntry(@Nullable AsyncQueryEntry asyncQueryEntry);

        boolean glowroot$hasAsyncQueryEntry();
    }

    // this is the main thread waiting on async result
    @Pointcut(className = "com.datastax.driver.core.ResultSetFuture",
            methodDeclaringClassName = "java.util.concurrent.Future", methodName = "get",
            methodParameterTypes = {".."})
    public static class FutureGetAdvice {
        @OnBefore
        public static @Nullable Timer onBefore(@BindReceiver ResultSetFutureMixin resultSetFuture) {
            AsyncQueryEntry asyncQueryEntry = resultSetFuture.glowroot$getAsyncQueryEntry();
            if (asyncQueryEntry == null) {
                return null;
            }
            return asyncQueryEntry.extendSyncTimer();
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable ResultSet resultSet,
                @BindReceiver ResultSetFutureMixin resultSetFuture) {
            if (resultSet == null) {
                return;
            }
            // pass query entry to the result set so it can be used when iterating the result set
            AsyncQueryEntry asyncQueryEntry = resultSetFuture.glowroot$getAsyncQueryEntry();
            resultSet.glowroot$setLastQueryEntry(asyncQueryEntry);
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Timer timer) {
            if (timer != null) {
                timer.stop();
            }
        }
    }

    // this is the main thread waiting on async result
    @Pointcut(className = "com.datastax.driver.core.ResultSetFuture",
            methodName = "getUninterruptibly", methodParameterTypes = {".."})
    public static class FutureGetUninterruptiblyAdvice {
        @OnBefore
        public static @Nullable Timer onBefore(@BindReceiver ResultSetFutureMixin resultSetFuture) {
            return FutureGetAdvice.onBefore(resultSetFuture);
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable ResultSet resultSet,
                @BindReceiver ResultSetFutureMixin resultSetFuture) {
            FutureGetAdvice.onReturn(resultSet, resultSetFuture);
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Timer timer) {
            FutureGetAdvice.onAfter(timer);
        }
    }

    @Pointcut(className = "com.datastax.driver.core.DefaultResultSetFuture",
            methodDeclaringClassName = "com.google.common.util.concurrent.AbstractFuture",
            methodName = "setException", methodParameterTypes = {"java.lang.Throwable"})
    public static class FutureSetExceptionAdvice {
        @OnReturn
        public static void onReturn(@BindReceiver ResultSetFutureMixin resultSetFuture,
                @BindParameter @Nullable Throwable t) {
            if (t == null) {
                return;
            }
            AsyncQueryEntry asyncQueryEntry = resultSetFuture.glowroot$getAsyncQueryEntry();
            if (asyncQueryEntry == null) {
                return;
            }
            asyncQueryEntry.endWithError(t);
        }
    }

    @Pointcut(className = "com.datastax.driver.core.DefaultResultSetFuture",
            methodDeclaringClassName = "com.google.common.util.concurrent.AbstractFuture",
            methodName = "set", methodParameterTypes = {"java.lang.Object"})
    public static class FutureSetAdvice {
        @OnReturn
        public static void onReturn(@BindReceiver ResultSetFutureMixin resultSetFuture) {
            AsyncQueryEntry asyncQueryEntry = resultSetFuture.glowroot$getAsyncQueryEntry();
            if (asyncQueryEntry == null) {
                return;
            }
            asyncQueryEntry.end();
        }
    }
}
