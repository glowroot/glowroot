/*
 * Copyright 2016-2023 the original author or authors.
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

import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.glowroot.agent.plugin.api.*;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.weaving.*;
import org.glowroot.agent.plugin.httpclient.bclglowrootbcl.Uris;

// see nearly identical copy of this in WiremockApacheHttpClientAspect
public class ApacheHttpClient5xAspect {

    @Pointcut(className = "org.apache.hc.client5.http.impl.classic.CloseableHttpClient", methodName = "doExecute",
            methodParameterTypes = {"org.apache.hc.core5.http.HttpHost", "org.apache.hc.core5.http.ClassicHttpRequest",
                    "org.apache.hc.core5.http.protocol.HttpContext"},
            nestingGroup = "http-client", timerName = "http client request")
    public static class ExecuteAdvice {
        private static final TimerName timerName = Agent.getTimerName(ExecuteAdvice.class);

        @OnBefore
        public static @Nullable TraceEntry onBefore(ThreadContext context,
                                                    @BindParameter @Nullable HttpHost hostObj,
                                                    @BindParameter @Nullable ClassicHttpRequest request) {
            if (request == null) {
                return null;
            }
            String method = request.getMethod();
            if (method == null) {
                method = "";
            } else {
                method += " ";
            }
            String host = hostObj == null ? "" : hostObj.toURI();
            String uri = request.getRequestUri();
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
                                   @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithError(t);
            }
        }
    }
}
