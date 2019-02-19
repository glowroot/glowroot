/*
 * Copyright 2016-2019 the original author or authors.
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

import java.net.URL;

import com.squareup.okhttp.Callback;
import com.squareup.okhttp.Request;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.AsyncTraceEntry;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ParameterHolder;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.weaving.BindClassMeta;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.httpclient._.OkHttpClientCallInvoker;
import org.glowroot.agent.plugin.httpclient._.Uris;

public class OkHttpClient2xAspect {

    @Pointcut(className = "com.squareup.okhttp.Call", methodName = "execute",
            methodParameterTypes = {}, nestingGroup = "http-client",
            timerName = "http client request")
    public static class ExecuteAdvice {
        private static final TimerName timerName = Agent.getTimerName(ExecuteAdvice.class);
        @OnBefore
        public static @Nullable TraceEntry onBefore(ThreadContext context,
                @BindReceiver Object call, @BindClassMeta OkHttpClientCallInvoker callInvoker) {
            Request originalRequest = (Request) callInvoker.getOriginalRequest(call);
            if (originalRequest == null) {
                return null;
            }
            String method = originalRequest.method();
            if (method == null) {
                method = "";
            } else {
                method += " ";
            }
            String url = originalRequest.urlString();
            return context.startServiceCallEntry("HTTP", method + Uris.stripQueryString(url),
                    MessageSupplier.create("http client request: {}{}", method, url), timerName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.end();
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithError(t);
            }
        }
    }

    @Pointcut(className = "com.squareup.okhttp.Call", methodName = "enqueue",
            methodParameterTypes = {"com.squareup.okhttp.Callback"}, nestingGroup = "http-client",
            timerName = "http client request")
    public static class EnqueueAdvice {
        private static final TimerName timerName = Agent.getTimerName(EnqueueAdvice.class);
        @OnBefore
        public static @Nullable AsyncTraceEntry onBefore(ThreadContext context,
                @BindReceiver Object call, @BindParameter ParameterHolder<Callback> callback,
                @BindClassMeta OkHttpClientCallInvoker callInvoker) {
            Request originalRequest = (Request) callInvoker.getOriginalRequest(call);
            if (originalRequest == null) {
                return null;
            }
            if (callback == null) {
                return null;
            }
            String method = originalRequest.method();
            if (method == null) {
                method = "";
            } else {
                method += " ";
            }
            URL urlObj = originalRequest.url();
            String url;
            if (urlObj == null) {
                url = "";
            } else {
                url = urlObj.toString();
            }
            AsyncTraceEntry asyncTraceEntry = context.startAsyncServiceCallEntry("HTTP",
                    method + Uris.stripQueryString(url),
                    MessageSupplier.create("http client request: {}{}", method, url), timerName);
            callback.set(createWrapper(context, callback, asyncTraceEntry));
            return asyncTraceEntry;
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable AsyncTraceEntry asyncTraceEntry) {
            if (asyncTraceEntry != null) {
                asyncTraceEntry.stopSyncTimer();
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable AsyncTraceEntry asyncTraceEntry) {
            if (asyncTraceEntry != null) {
                asyncTraceEntry.stopSyncTimer();
                asyncTraceEntry.endWithError(t);
            }
        }
        private static Callback createWrapper(ThreadContext context,
                ParameterHolder<Callback> callback, AsyncTraceEntry asyncTraceEntry) {
            Callback delegate = callback.get();
            if (delegate == null) {
                return new OkHttp2xCallbackWrapperForNullDelegate(asyncTraceEntry);
            } else {
                return new OkHttp2xCallbackWrapper(delegate, asyncTraceEntry,
                        context.createAuxThreadContext());
            }
        }
    }
}
