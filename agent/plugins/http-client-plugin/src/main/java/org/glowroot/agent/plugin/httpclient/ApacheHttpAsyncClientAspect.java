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
package org.glowroot.agent.plugin.httpclient;

import java.net.URI;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.AsyncTraceEntry;
import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.util.FastThreadLocal;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.httpclient.ApacheHttpClientAspect.HttpHost;
import org.glowroot.agent.plugin.httpclient.ApacheHttpClientAspect.HttpRequest;
import org.glowroot.agent.plugin.httpclient.ApacheHttpClientAspect.HttpUriRequest;
import org.glowroot.agent.plugin.httpclient.ApacheHttpClientAspect.RequestLine;

public class ApacheHttpAsyncClientAspect {

    private static final FastThreadLocal</*@Nullable*/ AsyncTraceEntry> asyncTraceEntryHolder =
            new FastThreadLocal</*@Nullable*/ AsyncTraceEntry>();

    // the field and method names are verbose since they will be mixed in to existing classes
    @Mixin("org.apache.http.nio.protocol.HttpAsyncResponseConsumer")
    public abstract static class HttpAsyncResponseConsumerImpl
            implements HttpAsyncResponseConsumerMixin {

        private volatile @Nullable AsyncTraceEntry glowroot$asyncTraceEntry;

        @Override
        public @Nullable AsyncTraceEntry glowroot$getAsyncTraceEntry() {
            return glowroot$asyncTraceEntry;
        }

        @Override
        public void glowroot$setAsyncTraceEntry(@Nullable AsyncTraceEntry asyncTraceEntry) {
            glowroot$asyncTraceEntry = asyncTraceEntry;
        }
    }

    // the field and method names are verbose since they will be mixed in to existing classes
    @Mixin("org.apache.http.concurrent.FutureCallback")
    public abstract static class FutureCallbackImpl implements FutureCallbackMixin {

        private volatile @Nullable AuxThreadContext glowroot$auxContext;

        @Override
        public @Nullable AuxThreadContext glowroot$getAuxContext() {
            return glowroot$auxContext;
        }

        @Override
        public void glowroot$setAuxContext(@Nullable AuxThreadContext auxContext) {
            glowroot$auxContext = auxContext;
        }
    }

    // the method names are verbose since they will be mixed in to existing classes
    public interface HttpAsyncResponseConsumerMixin {

        @Nullable
        AsyncTraceEntry glowroot$getAsyncTraceEntry();

        void glowroot$setAsyncTraceEntry(@Nullable AsyncTraceEntry asyncTraceEntry);
    }

    // the method names are verbose since they will be mixed in to existing classes
    public interface FutureCallbackMixin {

        @Nullable
        AuxThreadContext glowroot$getAuxContext();

        void glowroot$setAuxContext(@Nullable AuxThreadContext auxContext);
    }

    @Pointcut(className = "org.apache.http.nio.client.HttpAsyncClient", methodName = "execute",
            methodParameterTypes = {"org.apache.http.client.methods.HttpUriRequest", ".."},
            nestingGroup = "http-client", timerName = "http client request")
    public static class ExecuteAdvice {
        private static final TimerName timerName = Agent.getTimerName(ExecuteAdvice.class);
        @OnBefore
        public static @Nullable AsyncTraceEntry onBefore(ThreadContext context,
                @BindParameter @Nullable HttpUriRequest request) {
            if (request == null) {
                return null;
            }
            String method = request.getMethod();
            if (method == null) {
                method = "";
            } else {
                method += " ";
            }
            URI uriObj = request.getURI();
            String uri;
            if (uriObj == null) {
                uri = "";
            } else {
                uri = uriObj.toString();
            }
            AsyncTraceEntry asyncTraceEntry = context.startAsyncServiceCallEntry("HTTP",
                    method + Uris.stripQueryString(uri),
                    MessageSupplier.create("http client request: {}{}", method, uri), timerName);
            asyncTraceEntryHolder.set(asyncTraceEntry);
            return asyncTraceEntry;
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable AsyncTraceEntry asyncTraceEntry) {
            if (asyncTraceEntry != null) {
                asyncTraceEntry.stopSyncTimer();
                asyncTraceEntryHolder.set(null);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable AsyncTraceEntry asyncTraceEntry) {
            if (asyncTraceEntry != null) {
                asyncTraceEntry.stopSyncTimer();
                asyncTraceEntry.endWithError(t);
                asyncTraceEntryHolder.set(null);
            }
        }
    }

    @Pointcut(className = "org.apache.http.nio.client.HttpAsyncClient", methodName = "execute",
            methodParameterTypes = {"org.apache.http.HttpHost", "org.apache.http.HttpRequest",
                    ".."},
            nestingGroup = "http-client", timerName = "http client request")
    public static class ExecuteWithHostAdvice {
        private static final TimerName timerName = Agent.getTimerName(ExecuteWithHostAdvice.class);
        @OnBefore
        public static @Nullable AsyncTraceEntry onBefore(ThreadContext context,
                @BindParameter @Nullable HttpHost hostObj,
                @BindParameter @Nullable HttpRequest request) {
            if (request == null) {
                return null;
            }
            RequestLine requestLine = request.glowroot$getRequestLine();
            if (requestLine == null) {
                return null;
            }
            String method = requestLine.getMethod();
            if (method == null) {
                method = "";
            } else {
                method += " ";
            }
            String host = hostObj == null ? "" : hostObj.toURI();
            String uri = requestLine.getUri();
            if (uri == null) {
                uri = "";
            }
            AsyncTraceEntry asyncTraceEntry = context.startAsyncServiceCallEntry("HTTP",
                    method + Uris.stripQueryString(uri),
                    MessageSupplier.create("http client request: {}{}{}", method, host, uri),
                    timerName);
            asyncTraceEntryHolder.set(asyncTraceEntry);
            return asyncTraceEntry;
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable AsyncTraceEntry asyncTraceEntry) {
            if (asyncTraceEntry != null) {
                asyncTraceEntry.stopSyncTimer();
                asyncTraceEntryHolder.set(null);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable AsyncTraceEntry asyncTraceEntry) {
            if (asyncTraceEntry != null) {
                asyncTraceEntry.stopSyncTimer();
                asyncTraceEntry.endWithError(t);
                asyncTraceEntryHolder.set(null);
            }
        }
    }

    @Pointcut(className = "org.apache.http.nio.client.HttpAsyncClient", methodName = "execute",
            methodParameterTypes = {"org.apache.http.nio.protocol.HttpAsyncRequestProducer",
                    "org.apache.http.nio.protocol.HttpAsyncResponseConsumer",
                    "org.apache.http.concurrent.FutureCallback"},
            nestingGroup = "http-client-producer-consumer")
    public static class ExecuteWithProducerConsumerAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context,
                @SuppressWarnings("unused") @BindParameter @Nullable Object producer,
                @BindParameter @Nullable HttpAsyncResponseConsumerMixin consumer,
                @BindParameter @Nullable FutureCallbackMixin callback) {
            AsyncTraceEntry asyncTraceEntry = asyncTraceEntryHolder.get();
            if (asyncTraceEntry == null) {
                return;
            }
            if (consumer != null) {
                consumer.glowroot$setAsyncTraceEntry(asyncTraceEntry);
            }
            if (callback != null) {
                callback.glowroot$setAuxContext(context.createAuxThreadContext());
            }
        }
    }

    @Pointcut(className = "org.apache.http.nio.client.HttpAsyncClient", methodName = "execute",
            methodParameterTypes = {"org.apache.http.nio.protocol.HttpAsyncRequestProducer",
                    "org.apache.http.nio.protocol.HttpAsyncResponseConsumer",
                    "org.apache.http.protocol.HttpContext",
                    "org.apache.http.concurrent.FutureCallback"})
    public static class ExecuteWithProducerConsumerContextAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context, @BindParameter @Nullable Object producer,
                @BindParameter @Nullable HttpAsyncResponseConsumerMixin consumer,
                @SuppressWarnings("unused") @BindParameter @Nullable Object httpContext,
                @BindParameter @Nullable FutureCallbackMixin callback) {
            ExecuteWithProducerConsumerAdvice.onBefore(context, producer, consumer, callback);
        }
    }

    @Pointcut(className = "org.apache.http.nio.protocol.HttpAsyncResponseConsumer",
            methodName = "responseCompleted",
            methodParameterTypes = {"org.apache.http.protocol.HttpContext"})
    public static class ResponseCompletedAdvice {
        @OnBefore
        public static void onBefore(@BindReceiver HttpAsyncResponseConsumerMixin consumer) {
            AsyncTraceEntry asyncTraceEntry = consumer.glowroot$getAsyncTraceEntry();
            if (asyncTraceEntry != null) {
                asyncTraceEntry.end();
            }
        }
    }

    @Pointcut(className = "org.apache.http.nio.protocol.HttpAsyncResponseConsumer",
            methodName = "failed", methodParameterTypes = {"java.util.Exception"})
    public static class FailedAdvice {
        @OnBefore
        public static void onBefore(@BindReceiver HttpAsyncResponseConsumerMixin consumer,
                @BindParameter @Nullable Exception exception) {
            AsyncTraceEntry asyncTraceEntry = consumer.glowroot$getAsyncTraceEntry();
            if (asyncTraceEntry == null) {
                return;
            }
            if (exception == null) {
                asyncTraceEntry.endWithError("");
            } else {
                asyncTraceEntry.endWithError(exception);
            }
        }
    }

    @Pointcut(className = "org.apache.http.concurrent.FutureCallback",
            methodName = "completed|cancelled|failed", methodParameterTypes = {".."})
    public static class CompletedCallbackAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return true;
        }
        @OnBefore
        public static @Nullable TraceEntry onBefore(@BindReceiver FutureCallbackMixin callback) {
            AuxThreadContext auxContext = callback.glowroot$getAuxContext();
            if (auxContext == null) {
                return null;
            }
            return auxContext.start();
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
}
