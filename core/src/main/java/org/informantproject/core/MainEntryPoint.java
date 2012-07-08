/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.core;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import org.h2.store.FileLister;
import org.informantproject.api.PluginServices;
import org.informantproject.core.configuration.ConfigurationService;
import org.informantproject.core.metric.MetricCache;
import org.informantproject.core.trace.PluginServicesImpl.PluginServicesImplFactory;
import org.informantproject.core.trace.TraceRegistry;
import org.informantproject.core.weaving.InformantClassFileTransformer;
import org.informantproject.local.ui.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.google.common.collect.Lists;
import com.google.inject.Guice;
import com.google.inject.Injector;

/**
 * This class is registered as the Premain-Class in the MANIFEST.MF of informant-core.jar:
 * 
 * Premain-Class: org.informantproject.core.MainEntryPoint
 * 
 * This defines the entry point when the JVM is launched via -javaagent:informant-core.jar. This
 * class starts various background threads and then starts the AspectJ load-time weaving agent.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public final class MainEntryPoint {

    private static final Logger logger = LoggerFactory.getLogger(MainEntryPoint.class);

    private static volatile Injector injector;
    private static final Object lock = new Object();

    // when running unit tests under IsolatedWeavingClassLoader, plugins may get instantiated by
    // aspectj and request their PluginServices instance before Informant has finished starting up,
    // in which case they are given a proxy which will point to the real PluginServices as soon as
    // possible. this is not an issue when running under javaagent, since in that case Informant is
    // started up before adding the aspectj weaving agent (InformantClassFileTransformer).
    @GuardedBy("returnPluginServicesProxy")
    private static final List<PluginServicesProxy> pluginServicesProxies =
            new ArrayList<PluginServicesProxy>();
    private static final AtomicBoolean returnPluginServicesProxy = new AtomicBoolean(true);

    private MainEntryPoint() {}

    // javaagent entry point
    public static void premain(@Nullable String agentArgs, Instrumentation instrumentation) {
        logger.debug("premain(): agentArgs={}", agentArgs);
        AgentArgs parsedAgentArgs = new AgentArgs(agentArgs);
        try {
            File h2DatabaseFile = new File(parsedAgentArgs.getDataDir(), "informant.lock.db");
            FileLister.tryUnlockDatabase(Lists.newArrayList(h2DatabaseFile.getPath()), null);
        } catch (SQLException e) {
            // this is common when stopping tomcat since 'catalina.sh stop' launches a java process
            // to stop the tomcat jvm, and it uses the same JAVA_OPTS environment variable that may
            // have been used to specify '-javaagent:informant.jar', in which case Informant tries
            // to start up, but it finds the h2 database is locked (by the tomcat jvm).
            // this can sometimes be avoided by using CATALINA_OPTS instead of JAVA_OPTS to specify
            // -javaagent:informant.jar, since CATALINA_OPTS is not used by the 'catalina.sh stop'.
            // however, when running tomcat from inside eclipse, the tomcat server adapter uses the
            // same 'VM arguments' for both starting and stopping tomcat, so this code path seems
            // inevitable at least in this case, and probably others
            logger.error("Disabling Informant. Embedded database '" + parsedAgentArgs.getDataDir()
                    .getAbsolutePath() + "' is locked by another process.");
            return;
        }
        start(parsedAgentArgs);
        TraceRegistry traceRegistry = injector.getInstance(TraceRegistry.class);
        Ticker ticker = injector.getInstance(Ticker.class);
        instrumentation.addTransformer(new InformantClassFileTransformer(traceRegistry, ticker));
    }

    // called via reflection from org.informantproject.api.PluginServices
    public static PluginServices createPluginServices(String pluginId) {
        if (returnPluginServicesProxy.get()) {
            synchronized (returnPluginServicesProxy) {
                if (returnPluginServicesProxy.get()) {
                    MetricCache metricCache = injector.getInstance(MetricCache.class);
                    PluginServicesProxy proxy = new PluginServicesProxy(pluginId, metricCache);
                    pluginServicesProxies.add(proxy);
                    return proxy;
                }
            }
        }
        return injector.getInstance(PluginServicesImplFactory.class).create(pluginId);
    }

    @VisibleForTesting
    public static void start() {
        start(new AgentArgs());
    }

    @VisibleForTesting
    public static void start(String agentArgs) {
        start(new AgentArgs(agentArgs));
    }

    private static void start(AgentArgs agentArgs) {
        logger.debug("start(): classLoader={}", MainEntryPoint.class.getClassLoader());
        synchronized (lock) {
            if (injector != null) {
                throw new IllegalStateException("Informant is already started");
            }
            injector = Guice.createInjector(new InformantModule(agentArgs));
            InformantModule.start(injector);
        }
        returnPluginServicesProxy.set(false);
        synchronized (returnPluginServicesProxy) {
            for (PluginServicesProxy proxy : pluginServicesProxies) {
                proxy.start(injector.getInstance(PluginServicesImplFactory.class), injector
                        .getInstance(ConfigurationService.class));
            }
            // don't need reference to these proxies anymore, may as well clean up
            pluginServicesProxies.clear();
        }
    }

    @VisibleForTesting
    public static int getPort() {
        return injector.getInstance(HttpServer.class).getPort();
    }

    @VisibleForTesting
    public static void shutdown() {
        logger.debug("shutdown()");
        synchronized (lock) {
            if (injector == null) {
                throw new IllegalStateException("Informant is not started");
            }
            InformantModule.shutdown(injector);
            injector = null;
        }
    }
}
