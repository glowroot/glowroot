/*
 * Copyright 2015-2017 the original author or authors.
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

import java.util.concurrent.Executor;
import java.util.concurrent.Future;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.AsyncTraceEntry;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.util.FastThreadLocal;
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
import org.glowroot.agent.plugin.api.weaving.Shim;

public class AsyncHttpClientAspect {

    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private static final FastThreadLocal<Boolean> ignoreFutureGet = new FastThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    @Shim("org.asynchttpclient.Request")
    public interface Request {

        @Nullable
        String getMethod();

        @Nullable
        String getUrl();
    }

    @Shim("com.ning.http.client.Request")
    public interface OldRequest {
        @Nullable
        String getMethod();
    }

    // the field and method names are verbose since they will be mixed in to existing classes
    @Mixin({"org.asynchttpclient.ListenableFuture", "com.ning.http.client.ListenableFuture"})
    public abstract static class ListenableFutureImpl implements ListenableFutureMixin {

        // volatile not needed, only accessed by the main thread
        private @Nullable AsyncTraceEntry glowroot$asyncTraceEntry;

        @Override
        public @Nullable AsyncTraceEntry glowroot$getAsyncTraceEntry() {
            return glowroot$asyncTraceEntry;
        }

        @Override
        public void glowroot$setAsyncTraceEntry(@Nullable AsyncTraceEntry asyncTraceEntry) {
            glowroot$asyncTraceEntry = asyncTraceEntry;
        }
    }

    // the method names are verbose since they will be mixed in to existing classes
    public interface ListenableFutureMixin {

        @Nullable
        AsyncTraceEntry glowroot$getAsyncTraceEntry();

        void glowroot$setAsyncTraceEntry(@Nullable AsyncTraceEntry asyncTraceEntry);
    }

    public interface ListenableFutureShim<V> extends Future<V> {
        Object glowroot$addListener(Runnable listener, Executor exec);
    }

    @Shim("org.asynchttpclient.ListenableFuture")
    public interface NewListenableFutureShim<V> extends ListenableFutureShim<V> {

        @Override
        @Shim("org.asynchttpclient.ListenableFuture"
                + " addListener(java.lang.Runnable, java.util.concurrent.Executor)")
        Object glowroot$addListener(Runnable listener, Executor exec);
    }

    @Shim("com.ning.http.client.ListenableFuture")
    public interface OldListenableFutureShim<V> extends ListenableFutureShim<V> {

        @Override
        @Shim("com.ning.http.client.ListenableFuture"
                + " addListener(java.lang.Runnable, java.util.concurrent.Executor)")
        Object glowroot$addListener(Runnable listener, Executor exec);
    }

    @Pointcut(className = "org.asynchttpclient.AsyncHttpClient", methodName = "executeRequest",
            methodParameterTypes = {"org.asynchttpclient.Request", ".."},
            methodReturnType = "org.asynchttpclient.ListenableFuture",
            nestingGroup = "http-client", timerName = "http client request")
    public static class ExecuteRequestAdvice {
        private static final TimerName timerName = Agent.getTimerName(ExecuteRequestAdvice.class);
        @OnBefore
        public static @Nullable AsyncTraceEntry onBefore(ThreadContext context,
                @BindParameter @Nullable Request request) {
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
            String url = request.getUrl();
            if (url == null) {
                url = "";
            }
            return context.startAsyncServiceCallEntry("HTTP", method + Uris.stripQueryString(url),
                    MessageSupplier.create("http client request: {}{}", method, url), timerName);
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable ListenableFutureMixin future,
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
            final ListenableFutureShim<?> listenableFuture = (ListenableFutureShim<?>) future;
            listenableFuture.glowroot$addListener(new Runnable() {
                // suppress warnings is needed because checker framework doesn't see that
                // asyncTraceEntry must be non-null here
                @Override
                @SuppressWarnings("dereference.of.nullable")
                public void run() {
                    Throwable t = getException(listenableFuture);
                    if (t == null) {
                        asyncTraceEntry.end();
                    } else {
                        asyncTraceEntry.endWithError(t);
                    }
                }
            }, DirectExecutor.INSTANCE);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable AsyncTraceEntry asyncTraceEntry) {
            if (asyncTraceEntry != null) {
                asyncTraceEntry.stopSyncTimer();
                asyncTraceEntry.endWithError(t);
            }
        }
        // this is hacky way to find out if future ended with exception or not
        private static @Nullable Throwable getException(ListenableFutureShim<?> future) {
            ignoreFutureGet.set(true);
            try {
                future.get();
            } catch (Throwable t) {
                return t;
            } finally {
                ignoreFutureGet.set(false);
            }
            return null;
        }
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
                @BindParameter @Nullable OldRequest request,
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
        public static void onReturn(@BindReturn @Nullable ListenableFutureMixin future,
                final @BindTraveler @Nullable AsyncTraceEntry asyncTraceEntry) {
            ExecuteRequestAdvice.onReturn(future, asyncTraceEntry);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable AsyncTraceEntry asyncTraceEntry) {
            ExecuteRequestAdvice.onThrow(t, asyncTraceEntry);
        }
    }

    @Pointcut(className = "java.util.concurrent.Future",
            subTypeRestriction = "org.asynchttpclient.ListenableFuture"
                    + "|com.ning.http.client.ListenableFuture",
            methodName = "get", methodParameterTypes = {".."}, suppressionKey = "wait-on-future")
    public static class FutureGetAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return !ignoreFutureGet.get();
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

    private static class DirectExecutor implements Executor {

        private static final DirectExecutor INSTANCE = new DirectExecutor();

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
