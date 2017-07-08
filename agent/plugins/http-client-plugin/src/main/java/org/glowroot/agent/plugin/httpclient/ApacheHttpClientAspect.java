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
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

public class ApacheHttpClientAspect {

    @Shim("org.apache.http.HttpRequest")
    public interface UnshadedHttpRequest extends HttpRequest {

        @Override
        @Shim("org.apache.http.RequestLine getRequestLine()")
        @Nullable
        RequestLine glowroot$getRequestLine();
    }

    @Shim("wiremock.org.apache.http.HttpRequest")
    public interface WireMockShadedHttpRequest extends HttpRequest {

        @Override
        @Shim("wiremock.org.apache.http.RequestLine getRequestLine()")
        @Nullable
        RequestLine glowroot$getRequestLine();
    }

    public interface HttpRequest {

        @Nullable
        RequestLine glowroot$getRequestLine();
    }

    @Shim({"org.apache.http.RequestLine", "wiremock.org.apache.http.RequestLine"})
    public interface RequestLine {

        @Nullable
        String getMethod();

        @Nullable
        String getUri();
    }

    @Shim({"org.apache.http.HttpHost", "wiremock.org.apache.http.HttpHost"})
    public interface HttpHost {
        @Nullable
        String toURI();
    }

    @Shim({"org.apache.http.client.methods.HttpUriRequest",
            "wiremock.org.apache.http.client.methods.HttpUriRequest"})
    public interface HttpUriRequest {

        @Nullable
        String getMethod();

        @Nullable
        URI getURI();
    }

    @Pointcut(className = "org.apache.http.client.HttpClient"
            + "|wiremock.org.apache.http.client.HttpClient", methodName = "execute",
            methodParameterTypes = {"org.apache.http.client.methods.HttpUriRequest"
                    + "|wiremock.org.apache.http.client.methods.HttpUriRequest", ".."},
            nestingGroup = "http-client", timerName = "http client request")
    public static class ExecuteAdvice {
        private static final TimerName timerName = Agent.getTimerName(ExecuteAdvice.class);
        @OnBefore
        public static @Nullable TraceEntry onBefore(ThreadContext context,
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
            return context.startServiceCallEntry("HTTP", method + Uris.stripQueryString(uri),
                    MessageSupplier.create("http client request: {}{}", method, uri),
                    timerName);
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

    @Pointcut(className = "org.apache.http.client.HttpClient"
            + "|wiremock.org.apache.http.client.HttpClient", methodName = "execute",
            methodParameterTypes = {"org.apache.http.HttpHost|wiremock.org.apache.http.HttpHost",
                    "org.apache.http.HttpRequest|wiremock.org.apache.http.HttpRequest", ".."},
            nestingGroup = "http-client", timerName = "http client request")
    public static class ExecuteWithHostAdvice {
        private static final TimerName timerName = Agent.getTimerName(ExecuteWithHostAdvice.class);
        @OnBefore
        public static @Nullable TraceEntry onBefore(ThreadContext context,
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
            return context.startServiceCallEntry("HTTP", method + Uris.stripQueryString(uri),
                    MessageSupplier.create("http client request: {}{}{}", method, host, uri),
                    timerName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.end();
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(t);
        }
    }
}
