/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.agent.plugin.mail;

import org.glowroot.agent.plugin.api.*;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.util.Beans;
import org.glowroot.agent.plugin.api.weaving.*;

import javax.mail.Session;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class MailAspect {

    private static final String SERVICE_CALL_TYPE = "Mail";

    private static final ConfigService configService = Agent.getConfigService("mail");

    // visibility is provided by memoryBarrier in org.glowroot.config.ConfigService
    private static int stackTraceThresholdMillis;

    static {
        configService.registerConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                Double value = configService.getDoubleProperty("stackTraceThresholdMillis").value();
                stackTraceThresholdMillis = value == null ? Integer.MAX_VALUE : value.intValue();
            }
        });
    }

    @Shim("javax.mail.Transport")
    public interface Transport {
    }

    @Pointcut(className = "javax.mail.Service",
            subTypeRestriction = "javax.mail.Transport",
            methodName = "connect",
            methodParameterTypes = {".."}, nestingGroup = "mail", timerName = "mail connect")
    public static class ServiceAdvice {

        private static final TimerName timerName = Agent.getTimerName(ServiceAdvice.class);

        @OnBefore
        public static @Nullable TraceEntry onBefore(ThreadContext context,
                @BindReceiver Transport transport, @BindMethodName String methodName) throws Exception {
            String protocol = transport.getClass().getSimpleName();
            return context.startServiceCallEntry(SERVICE_CALL_TYPE, "mail connect: " + protocol,
                    MessageSupplier.create("mail connect: " + protocol), timerName);
        }

        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithLocationStackTrace(stackTraceThresholdMillis, MILLISECONDS);
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

    @Pointcut(className = "javax.mail.Transport",
            methodName = "sendMessage",
            methodParameterTypes = {".."}, nestingGroup = "mail", timerName = "mail send")
    public static class TransportAdvice {

        private static final TimerName timerName = Agent.getTimerName(TransportAdvice.class);

        @OnBefore
        public static @Nullable TraceEntry onBefore(ThreadContext context, @BindMethodName String methodName) {
            return context.startServiceCallEntry(SERVICE_CALL_TYPE, "mail send",
                    MessageSupplier.create("mail send: message"), timerName);
        }

        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithLocationStackTrace(stackTraceThresholdMillis, MILLISECONDS);
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
