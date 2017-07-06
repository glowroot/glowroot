/*
 * Copyright 2017 the original author or authors.
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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class HttpURLConnectionAspect {

    // the field and method names are verbose since they will be mixed in to existing classes
    @Mixin({"sun.net.www.protocol.http.HttpURLConnection",
            "sun.net.www.protocol.https.HttpsURLConnectionImpl",
            "sun.net.www.protocol.http.HttpURLConnection$HttpInputStream",
            "sun.net.www.protocol.http.HttpURLConnection$StreamingOutputStream",
            "sun.net.www.http.PosterOutputStream"})
    public static class HasTraceEntryImpl implements HasTraceEntry {

        private @Nullable TraceEntry glowroot$traceEntry;

        @Override
        public @Nullable TraceEntry glowroot$getTraceEntry() {
            return glowroot$traceEntry;
        }

        @Override
        public void glowroot$setTraceEntry(@Nullable TraceEntry traceEntry) {
            glowroot$traceEntry = traceEntry;
        }

        @Override
        public boolean glowroot$hasTraceEntry() {
            return glowroot$traceEntry != null;
        }
    }

    // the method names are verbose since they will be mixed in to existing classes
    public interface HasTraceEntry {

        @Nullable
        TraceEntry glowroot$getTraceEntry();

        void glowroot$setTraceEntry(@Nullable TraceEntry traceEntry);

        boolean glowroot$hasTraceEntry();
    }

    @Pointcut(className = "java.net.URLConnection",
            subTypeRestriction = "java.net.HttpURLConnection", methodName = "connect",
            methodParameterTypes = {}, nestingGroup = "http-client", timerName = "http client")
    public static class ConnectAdvice {
        private static final TimerName timerName = Agent.getTimerName(ConnectAdvice.class);
        @OnBefore
        public static @Nullable Object onBefore(ThreadContext threadContext,
                @BindReceiver HttpURLConnection httpURLConnection) {
            return onBefore(threadContext, httpURLConnection, false);
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable Object entryOrTimer) {
            if (entryOrTimer instanceof TraceEntry) {
                ((TraceEntry) entryOrTimer).end();
            } else if (entryOrTimer instanceof Timer) {
                ((Timer) entryOrTimer).stop();
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable Object entryOrTimer) {
            if (entryOrTimer instanceof TraceEntry) {
                ((TraceEntry) entryOrTimer).endWithError(t);
            } else if (entryOrTimer instanceof Timer) {
                ((Timer) entryOrTimer).stop();
            }
        }
        private static @Nullable Object onBefore(ThreadContext threadContext,
                HttpURLConnection httpURLConnection, boolean overrideGetWithPost) {
            if (!(httpURLConnection instanceof HasTraceEntry)) {
                return null;
            }
            TraceEntry traceEntry = ((HasTraceEntry) httpURLConnection).glowroot$getTraceEntry();
            if (traceEntry != null) {
                return traceEntry.extend();
            }
            String method = httpURLConnection.getRequestMethod();
            if (method == null) {
                method = "";
            } else if (overrideGetWithPost && method.equals("GET")) {
                // this is to match behavior in
                // sun.net.www.protocol.http.HttpURLConnection.getOutputStream0()
                method = "POST ";
            } else {
                method += " ";
            }
            URL urlObj = httpURLConnection.getURL();
            String url;
            if (urlObj == null) {
                url = "";
            } else {
                url = urlObj.toString();
            }
            traceEntry = threadContext.startServiceCallEntry("HTTP",
                    method + Uris.stripQueryString(url),
                    MessageSupplier.create("http client request: {}{}", method, url), timerName);
            ((HasTraceEntry) httpURLConnection).glowroot$setTraceEntry(traceEntry);
            return traceEntry;
        }
    }

    @Pointcut(className = "java.net.URLConnection",
            subTypeRestriction = "java.net.HttpURLConnection", methodName = "getInputStream",
            methodParameterTypes = {}, nestingGroup = "http-client")
    public static class GetInputStreamAdvice {
        @OnBefore
        public static @Nullable Object onBefore(ThreadContext threadContext,
                @BindReceiver HttpURLConnection httpURLConnection) {
            return ConnectAdvice.onBefore(threadContext, httpURLConnection, false);
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable Object returnValue,
                @BindReceiver HttpURLConnection httpURLConnection,
                @BindTraveler @Nullable Object entryOrTimer) {
            if (httpURLConnection instanceof HasTraceEntry
                    && returnValue instanceof HasTraceEntry) {
                TraceEntry traceEntry =
                        ((HasTraceEntry) httpURLConnection).glowroot$getTraceEntry();
                ((HasTraceEntry) returnValue).glowroot$setTraceEntry(traceEntry);
            }
            ConnectAdvice.onReturn(entryOrTimer);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable Object entryOrTimer) {
            ConnectAdvice.onThrow(t, entryOrTimer);
        }
    }

    @Pointcut(className = "java.net.URLConnection",
            subTypeRestriction = "java.net.HttpURLConnection", methodName = "getOutputStream",
            methodParameterTypes = {}, nestingGroup = "http-client")
    public static class GetOutputStreamAdvice {
        @OnBefore
        public static @Nullable Object onBefore(ThreadContext threadContext,
                @BindReceiver HttpURLConnection httpURLConnection) {
            return ConnectAdvice.onBefore(threadContext, httpURLConnection, true);
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable Object returnValue,
                @BindReceiver HttpURLConnection httpURLConnection,
                @BindTraveler @Nullable Object entryOrTimer) {
            GetInputStreamAdvice.onReturn(returnValue, httpURLConnection, entryOrTimer);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable Object entryOrTimer) {
            ConnectAdvice.onThrow(t, entryOrTimer);
        }
    }

    @Pointcut(className = "sun.net.www.protocol.http.HttpURLConnection$HttpInputStream",
            methodName = "*", methodParameterTypes = {".."})
    public static class HttpInputStreamAdvice {
        @OnBefore
        public static @Nullable Timer onBefore(@BindReceiver InputStream httpInputStream) {
            if (!(httpInputStream instanceof HasTraceEntry)) {
                return null;
            }
            TraceEntry traceEntry = ((HasTraceEntry) httpInputStream).glowroot$getTraceEntry();
            if (traceEntry == null) {
                return null;
            }
            return traceEntry.extend();
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Timer timer) {
            if (timer != null) {
                timer.stop();
            }
        }
    }

    @Pointcut(className = "sun.net.www.protocol.http.HttpURLConnection$StreamingOutputStream"
            + "|sun.net.www.http.PosterOutputStream", methodName = "*",
            methodParameterTypes = {".."})
    public static class StreamingOutputStreamAdvice {
        @OnBefore
        public static @Nullable Timer onBefore(@BindReceiver OutputStream streamingOutputStream) {
            if (!(streamingOutputStream instanceof HasTraceEntry)) {
                return null;
            }
            TraceEntry traceEntry =
                    ((HasTraceEntry) streamingOutputStream).glowroot$getTraceEntry();
            if (traceEntry == null) {
                return null;
            }
            return traceEntry.extend();
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Timer timer) {
            if (timer != null) {
                timer.stop();
            }
        }
    }
}
