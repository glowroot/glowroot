/*
 * Copyright 2015-2016 the original author or authors.
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

import org.glowroot.agent.plugin.api.*;
import org.glowroot.agent.plugin.api.util.FastThreadLocal;
import org.glowroot.agent.plugin.api.weaving.*;

import javax.annotation.Nullable;
import javax.xml.namespace.QName;
import java.net.URI;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

public class SoapOutInterceptorAspect {

    @Shim("org.apache.cxf.binding.soap.SoapMessage")
    public interface SoapMessage {
        Object getContextualProperty(String key);
    }

    @Pointcut(className = "org.apache.cxf.binding.soap.interceptor.SoapOutInterceptor", methodName = "handleMessage",
            methodParameterTypes = {"org.apache.cxf.binding.soap.SoapMessage"},
            nestingGroup = "http-client", timerName = "cxf client soap request")
    public static class SoapOutInterceptorAdvice {
        private static final TimerName timerName = Agent.getTimerName(SoapOutInterceptorAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context, @BindParameter Object message) {
            // get uri
            String url = "";
            String operationName = "";
            if (message instanceof SoapMessage) {
                URI uri = (URI) ((SoapMessage) message).getContextualProperty("javax.xml.ws.wsdl.description");
                if (uri != null) {
                    url = uri.toString();
                    operationName = ((QName) ((SoapMessage) message).getContextualProperty("javax.xml.ws.wsdl.operation")).getLocalPart();
                }
            }
            return context.startTraceEntry(MessageSupplier.from("cxf client soap request: {} {}",
                    url, operationName), timerName);
        }

        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable throwable,
                                   @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(throwable);
        }
    }
}
