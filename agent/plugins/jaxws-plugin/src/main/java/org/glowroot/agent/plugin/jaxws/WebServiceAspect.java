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
package org.glowroot.agent.plugin.jaxws;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext.Priority;
import org.glowroot.agent.plugin.api.ThreadContext.ServletRequestInfo;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.config.BooleanProperty;
import org.glowroot.agent.plugin.api.weaving.BindMethodMeta;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class WebServiceAspect {

    private static final BooleanProperty useAltTransactionNaming =
            Agent.getConfigService("jaxws").getBooleanProperty("useAltTransactionNaming");

    @Pointcut(classAnnotation = "javax.jws.WebService", methodAnnotation = "javax.jws.WebMethod",
            methodParameterTypes = {".."}, timerName = "jaxws service")
    public static class ResourceAdvice {

        private static final TimerName timerName = Agent.getTimerName(ResourceAdvice.class);

        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindMethodMeta ServiceMethodMeta serviceMethodMeta) {

            if (useAltTransactionNaming.value()) {
                context.setTransactionName(serviceMethodMeta.getAltTransactionName(),
                        Priority.CORE_PLUGIN);
            } else {
                String transactionName = getTransactionName(context.getServletRequestInfo(),
                        serviceMethodMeta.getMethodName());
                context.setTransactionName(transactionName, Priority.CORE_PLUGIN);
            }
            return context.startTraceEntry(MessageSupplier.create("jaxws service: {}.{}()",
                    serviceMethodMeta.getServiceClassName(), serviceMethodMeta.getMethodName()),
                    timerName);
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

        private static String getTransactionName(@Nullable ServletRequestInfo servletRequestInfo,
                String methodName) {
            if (servletRequestInfo == null) {
                return '#' + methodName;
            }
            String method = servletRequestInfo.getMethod();
            String uri = servletRequestInfo.getUri();
            if (method.isEmpty()) {
                return uri + '#' + methodName;
            } else {
                return method + ' ' + uri + '#' + methodName;
            }
        }
    }
}
