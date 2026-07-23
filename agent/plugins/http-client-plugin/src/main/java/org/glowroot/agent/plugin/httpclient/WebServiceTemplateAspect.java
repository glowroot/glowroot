/*
 * Copyright 2026 the original author or authors.
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

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.httpclient.bclglowrootbcl.Uris;

// Spring WebServiceTemplate (SOAP) — RestTemplate is covered via HttpURLConnection;
// WebServiceTemplate needs its own service-call entry so calls show under Service Calls (#812).
public class WebServiceTemplateAspect {

    @Pointcut(className = "org.springframework.ws.client.core.WebServiceTemplate",
            methodName = "sendAndReceive",
            methodParameterTypes = {"java.lang.String",
                    "org.springframework.ws.client.core.WebServiceMessageCallback",
                    "org.springframework.ws.client.core.WebServiceMessageExtractor"},
            nestingGroup = "http-client", timerName = "http client request")
    public static class SendAndReceiveAdvice {

        private static final TimerName timerName = Agent.getTimerName(SendAndReceiveAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter @Nullable String uri) {
            String safeUri = uri == null ? "" : uri;
            return context.startServiceCallEntry("HTTP", "POST " + Uris.stripQueryString(safeUri),
                    MessageSupplier.create("http client request: POST {}", safeUri), timerName);
        }

        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(t);
        }
    }
}
