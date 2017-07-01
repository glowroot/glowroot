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

public class ApacheHttpClient3xAspect {

    @Shim("org.apache.commons.httpclient.HttpMethod")
    public interface HttpMethod {

        @Nullable
        String getName();

        @Shim("org.apache.commons.httpclient.URI getURI()")
        @Nullable
        Object glowroot$getURI();
    }

    @Pointcut(className = "org.apache.commons.httpclient.HttpClient", methodName = "executeMethod",
            methodParameterTypes = {"org.apache.commons.httpclient.HostConfiguration",
                    "org.apache.commons.httpclient.HttpMethod",
                    "org.apache.commons.httpclient.HttpState"},
            nestingGroup = "http-client", timerName = "http client request")
    public static class ExecuteMethodAdvice {
        private static final TimerName timerName = Agent.getTimerName(ExecuteMethodAdvice.class);
        @OnBefore
        public static @Nullable TraceEntry onBefore(ThreadContext context,
                @SuppressWarnings("unused") @BindParameter @Nullable Object hostConfiguration,
                @BindParameter @Nullable HttpMethod methodObj) {
            if (methodObj == null) {
                return null;
            }
            String method = methodObj.getName();
            if (method == null) {
                method = "";
            } else {
                method += " ";
            }
            Object uriObj = methodObj.glowroot$getURI();
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
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(t);
        }
    }
}
