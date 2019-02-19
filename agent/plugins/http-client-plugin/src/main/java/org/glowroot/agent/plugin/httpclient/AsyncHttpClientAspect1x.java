/*
 * Copyright 2015-2019 the original author or authors.
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
package org.glowroot.agent.plugin.httpclient;

import java.util.concurrent.Future;

import com.ning.http.client.ListenableFuture;
import com.ning.http.client.Request;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.AsyncTraceEntry;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.weaving.BindClassMeta;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.httpclient._.AsyncHttpClientRequestInvoker;
import org.glowroot.agent.plugin.httpclient._.DirectExecutor;
import org.glowroot.agent.plugin.httpclient._.Uris;

public class AsyncHttpClientAspect1x {

    // the field and method names are verbose since they will be mixed in to existing classes
    @Mixin("com.ning.http.client.ListenableFuture")
    public abstract static class ListenableFutureImpl implements ListenableFutureMixin {

        // volatile not needed, only accessed by the main thread
        private transient @Nullable AsyncTraceEntry glowroot$asyncTraceEntry;

        // volatile not needed, only accessed by the main thread
        private transient boolean glowroot$ignoreGet;

        @Override
        public @Nullable AsyncTraceEntry glowroot$getAsyncTraceEntry() {
            return glowroot$asyncTraceEntry;
        }

        @Override
        public void glowroot$setAsyncTraceEntry(@Nullable AsyncTraceEntry asyncTraceEntry) {
            glowroot$asyncTraceEntry = asyncTraceEntry;
        }

        @Override
        public boolean glowroot$getIgnoreGet() {
            return glowroot$ignoreGet;
        }

        @Override
        public void glowroot$setIgnoreGet(boolean ignoreGet) {
            glowroot$ignoreGet = ignoreGet;
        }
    }

    // the method names are verbose since they will be mixed in to existing classes
    // NOTE this interface cannot extend ListenableFuture since ListenableFuture extends this
    // interface after the mixin takes place
    public interface ListenableFutureMixin {

        @Nullable
        AsyncTraceEntry glowroot$getAsyncTraceEntry();

        void glowroot$setAsyncTraceEntry(@Nullable AsyncTraceEntry asyncTraceEntry);

        boolean glowroot$getIgnoreGet();

        void glowroot$setIgnoreGet(boolean value);
    }

    @Pointcut(className = "com.ning.http.client.AsyncHttpClient", methodName = "executeRequest",
            methodParameterTypes = {"com.ning.http.client.Request", ".."},
            methodReturnType = "com.ning.http.client.ListenableFuture",
            nestingGroup = "http-client", timerName = "http client request")
    public static class OldExecuteRequestAdvice {
        private static final TimerName timerName =
                Agent.getTimerName(OldExecuteRequestAdvice.class);
        @OnBefore
        public static @Nullable AsyncTraceEntry onBefore(ThreadContext context,
                @BindParameter @Nullable Request request,
                @BindClassMeta AsyncHttpClientRequestInvoker requestInvoker) {
            // need to start trace entry @OnBefore in case it is executed in a "same thread
            // executor" in which case will be over in @OnReturn
            if (request == null) {
                return null;
            }
            String method = request.getMethod();
            if (method == null) {
                method = "";
            } else {
                method += " ";
            }
            String url = requestInvoker.getUrl(request);
            return context.startAsyncServiceCallEntry("HTTP", method + Uris.stripQueryString(url),
                    MessageSupplier.create("http client request: {}{}", method, url), timerName);
        }
        @OnReturn
        public static <T extends ListenableFutureMixin & ListenableFuture<?>> void onReturn(
                final @BindReturn @Nullable T future,
                final @BindTraveler @Nullable AsyncTraceEntry asyncTraceEntry) {
            if (asyncTraceEntry == null) {
                return;
            }
            asyncTraceEntry.stopSyncTimer();
            if (future == null) {
                asyncTraceEntry.end();
                return;
            }
            future.glowroot$setAsyncTraceEntry(asyncTraceEntry);
            future.addListener(new ExecuteRequestListener<T>(asyncTraceEntry, future),
                    DirectExecutor.INSTANCE);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable AsyncTraceEntry asyncTraceEntry) {
            if (asyncTraceEntry != null) {
                asyncTraceEntry.stopSyncTimer();
                asyncTraceEntry.endWithError(t);
            }
        }
    }

    @Pointcut(className = "java.util.concurrent.Future",
            subTypeRestriction = "com.ning.http.client.ListenableFuture", methodName = "get",
            methodParameterTypes = {".."}, suppressionKey = "wait-on-future")
    public static class FutureGetAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver ListenableFutureMixin future) {
            return !future.glowroot$getIgnoreGet();
        }
        @OnBefore
        public static @Nullable Timer onBefore(ThreadContext threadContext,
                @BindReceiver ListenableFutureMixin future) {
            AsyncTraceEntry asyncTraceEntry = future.glowroot$getAsyncTraceEntry();
            if (asyncTraceEntry == null) {
                return null;
            }
            return asyncTraceEntry.extendSyncTimer(threadContext);
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Timer syncTimer) {
            if (syncTimer != null) {
                syncTimer.stop();
            }
        }
    }

    private static class ExecuteRequestListener<T extends ListenableFutureMixin & Future<?>>
            implements Runnable {

        private final AsyncTraceEntry asyncTraceEntry;
        private final T future;

        private ExecuteRequestListener(AsyncTraceEntry asyncTraceEntry, T future) {
            this.asyncTraceEntry = asyncTraceEntry;
            this.future = future;
        }

        @Override
        public void run() {
            Throwable t = getException();
            if (t == null) {
                asyncTraceEntry.end();
            } else {
                asyncTraceEntry.endWithError(t);
            }
        }

        // this is hacky way to find out if future ended with exception or not
        private @Nullable Throwable getException() {
            future.glowroot$setIgnoreGet(true);
            try {
                future.get();
            } catch (Throwable t) {
                return t;
            } finally {
                future.glowroot$setIgnoreGet(false);
            }
            return null;
        }
    }
}
