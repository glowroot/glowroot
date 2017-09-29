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
package org.glowroot.agent.plugin.servlet;

import java.lang.management.ManagementFactory;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.Logger;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext.Priority;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class ContainerStartup {

    private static final Logger logger = Agent.getLogger(Beans.class);

    private ContainerStartup() {}

    static TraceEntry onBeforeCommon(OptionalThreadContext context, @Nullable String path,
            TimerName timerName) {
        initPlatformMBeanServer();
        String transactionName;
        if (path == null || path.isEmpty()) {
            // root context path is empty "", but makes more sense to display "/"
            transactionName = "Servlet context: /";
        } else {
            transactionName = "Servlet context: " + path;
        }
        TraceEntry traceEntry = context.startTransaction("Startup", transactionName,
                MessageSupplier.create(transactionName), timerName);
        context.setTransactionSlowThreshold(0, MILLISECONDS, Priority.CORE_PLUGIN);
        return traceEntry;
    }

    static void initPlatformMBeanServer() {
        // make sure the platform mbean server gets created so that it can then be retrieved by
        // LazyPlatformMBeanServer which may be waiting for it to be created (the current
        // thread context class loader should have access to the platform mbean server that is set
        // via the javax.management.builder.initial system property)
        try {
            ManagementFactory.getPlatformMBeanServer();
        } catch (Throwable t) {
            logger.error("could not create platform mbean server: {}", t.getMessage(), t);
        }
    }
}
