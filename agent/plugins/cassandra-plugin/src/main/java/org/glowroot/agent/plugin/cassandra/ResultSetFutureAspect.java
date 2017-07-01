/*
 * Copyright 2016-2017 the original author or authors.
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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.AsyncQueryEntry;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.cassandra.ResultSetAspect.ResultSet;

public class ResultSetFutureAspect {

    // the field and method names are verbose since they will be mixed in to existing classes
    @Mixin("com.datastax.driver.core.ResultSetFuture")
    public static class ResultSetFutureImpl implements ResultSetFutureMixin {

        private volatile boolean glowroot$completed;
        private volatile @Nullable Throwable glowroot$exception;
        private volatile @Nullable AsyncQueryEntry glowroot$asyncQueryEntry;

        @Override
        public void glowroot$setCompleted() {
            glowroot$completed = true;
        }

        @Override
        public boolean glowroot$isCompleted() {
            return glowroot$completed;
        }

        @Override
        public void glowroot$setException(Throwable exception) {
            glowroot$exception = exception;
        }

        @Override
        public @Nullable Throwable glowroot$getException() {
            return glowroot$exception;
        }

        @Override
        public @Nullable AsyncQueryEntry glowroot$getAsyncQueryEntry() {
            return glowroot$asyncQueryEntry;
        }

        @Override
        public void glowroot$setAsyncQueryEntry(@Nullable AsyncQueryEntry asyncQueryEntry) {
            glowroot$asyncQueryEntry = asyncQueryEntry;
        }
    }

    // the method names are verbose since they will be mixed in to existing classes
    public interface ResultSetFutureMixin {

        void glowroot$setCompleted();

        boolean glowroot$isCompleted();

        void glowroot$setException(Throwable t);

        @Nullable
        Throwable glowroot$getException();

        @Nullable
        AsyncQueryEntry glowroot$getAsyncQueryEntry();

        void glowroot$setAsyncQueryEntry(@Nullable AsyncQueryEntry asyncQueryEntry);
    }

    @Pointcut(className = "java.util.concurrent.Future",
            subTypeRestriction = "com.datastax.driver.core.ResultSetFuture",
            methodName = "get", methodParameterTypes = {".."}, suppressionKey = "wait-on-future")
    public static class FutureGetAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver ResultSetFutureMixin resultSetFuture) {
            return resultSetFuture.glowroot$getAsyncQueryEntry() != null;
        }
        @OnBefore
        public static Timer onBefore(ThreadContext threadContext,
                @BindReceiver ResultSetFutureMixin resultSetFuture) {
            @SuppressWarnings("nullness") // just checked above in isEnabled()
            @Nonnull
            AsyncQueryEntry asyncQueryEntry = resultSetFuture.glowroot$getAsyncQueryEntry();
            return asyncQueryEntry.extendSyncTimer(threadContext);
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
        public static void onAfter(@BindTraveler Timer timer) {
            timer.stop();
        }
    }

    // waiting on async result
    @Pointcut(className = "com.datastax.driver.core.ResultSetFuture",
            methodName = "getUninterruptibly", methodParameterTypes = {".."})
    public static class FutureGetUninterruptiblyAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver ResultSetFutureMixin resultSetFuture) {
            return FutureGetAdvice.isEnabled(resultSetFuture);
        }
        @OnBefore
        public static Timer onBefore(ThreadContext threadContext,
                @BindReceiver ResultSetFutureMixin resultSetFuture) {
            return FutureGetAdvice.onBefore(threadContext, resultSetFuture);
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable ResultSet resultSet,
                @BindReceiver ResultSetFutureMixin resultSetFuture) {
            FutureGetAdvice.onReturn(resultSet, resultSetFuture);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Timer timer) {
            FutureGetAdvice.onAfter(timer);
        }
    }

    @Pointcut(className = "com.google.common.util.concurrent.AbstractFuture",
            subTypeRestriction = "com.datastax.driver.core.DefaultResultSetFuture",
            methodName = "setException", methodParameterTypes = {"java.lang.Throwable"})
    public static class FutureSetExceptionAdvice {
        // using @OnBefore instead of @OnReturn to ensure that async trace entry is ended prior to
        // an overall transaction that may be waiting on this future has a chance to end
        @OnBefore
        public static void onBefore(@BindReceiver ResultSetFutureMixin resultSetFuture,
                @BindParameter @Nullable Throwable t) {
            if (t == null) {
                return;
            }
            // to prevent race condition, setting completed/exception status before getting async
            // query entry, and the converse is done when setting async query entry
            // ok if end() happens to get called twice
            resultSetFuture.glowroot$setCompleted();
            resultSetFuture.glowroot$setException(t);
            AsyncQueryEntry asyncQueryEntry = resultSetFuture.glowroot$getAsyncQueryEntry();
            if (asyncQueryEntry != null) {
                asyncQueryEntry.endWithError(t);
            }
        }
    }

    @Pointcut(className = "com.google.common.util.concurrent.AbstractFuture",
            subTypeRestriction = "com.datastax.driver.core.DefaultResultSetFuture",
            methodName = "set", methodParameterTypes = {"java.lang.Object"})
    public static class FutureSetAdvice {
        // using @OnBefore instead of @OnReturn to ensure that async trace entry is ended prior to
        // an overall transaction that may be waiting on this future has a chance to end
        @OnBefore
        public static void onBefore(@BindReceiver ResultSetFutureMixin resultSetFuture) {
            // to prevent race condition, setting completed status before getting async query entry,
            // and the converse is done when setting async query entry
            // ok if end() happens to get called twice
            resultSetFuture.glowroot$setCompleted();
            AsyncQueryEntry asyncQueryEntry = resultSetFuture.glowroot$getAsyncQueryEntry();
            if (asyncQueryEntry != null) {
                asyncQueryEntry.end();
            }
        }
    }
}
