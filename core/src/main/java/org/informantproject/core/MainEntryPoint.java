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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import org.h2.store.FileLister;
import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.api.PluginServices;
import org.informantproject.api.weaving.Mixin;
import org.informantproject.core.PluginServicesImpl.PluginServicesImplFactory;
import org.informantproject.core.config.ConfigService;
import org.informantproject.core.config.PluginDescriptor;
import org.informantproject.core.config.Plugins;
import org.informantproject.core.trace.WeavingMetricImpl;
import org.informantproject.core.util.Static;
import org.informantproject.core.util.UnitTests.OnlyUsedByTests;
import org.informantproject.core.weaving.Advice;
import org.informantproject.core.weaving.WeavingClassFileTransformer;
import org.informantproject.core.weaving.WeavingMetric;
import org.informantproject.local.ui.HttpServer;

import com.google.common.base.Ticker;
import com.google.common.collect.Iterables;
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
@Static
public final class MainEntryPoint {

    private static final Logger logger = LoggerFactory.getLogger(MainEntryPoint.class);

    private static final WeavingMetricImpl weavingMetric = new WeavingMetricImpl(
            Ticker.systemTicker());

    @Nullable
    private static volatile Injector injector;
    private static final Object lock = new Object();

    // when running unit tests under IsolatedWeavingClassLoader, plugins may get instantiated by
    // aspectj and request their PluginServices instance before Informant has finished starting up,
    // in which case they are given a proxy which will point to the real PluginServices as soon as
    // possible. this is not an issue when running under javaagent, since in that case Informant is
    // started up before adding the aspectj weaving agent (InformantClassFileTransformer).
    @GuardedBy("returnPluginServicesProxy")
    private static final List<PluginServicesProxy> pluginServicesProxies = Lists.newArrayList();
    private static final AtomicBoolean returnPluginServicesProxy = new AtomicBoolean(true);

    // javaagent entry point
    public static void premain(@Nullable String agentArgs, Instrumentation instrumentation) {
        logger.debug("premain(): agentArgs={}", agentArgs);
        WeavingClassFileTransformer weavingClassFileTransformer = newWeavingClassFileTransformer();
        // add weaving agent as early as possible to avoid missing some classes that are loaded
        // indirectly by the Informant startup process, e.g. the H2 FileLister.tryUnlockDatabase()
        // call below triggers java.sql.DriverManager which looks on the classpath for
        // META-INF/services/java.sql.Driver files and loads their respective drivers which, at
        // least in the case of the Oracle driver, loads the driver's Connection and
        // PreparedStatement classes which need to be woven
        instrumentation.addTransformer(weavingClassFileTransformer);
        AgentArgs parsedAgentArgs = AgentArgs.from(agentArgs);
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
            logger.error("disabling Informant, embedded database '" + parsedAgentArgs.getDataDir()
                    .getAbsolutePath() + "' is locked by another process.");
            weavingClassFileTransformer.disable();
            return;
        }
        start(parsedAgentArgs);

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

    private static WeavingClassFileTransformer newWeavingClassFileTransformer() {
        List<Mixin> mixins = Lists.newArrayList();
        List<Advice> advisors = Lists.newArrayList();
        for (PluginDescriptor plugin : Plugins.getPackagedPluginDescriptors()) {
            mixins.addAll(plugin.getMixins());
            advisors.addAll(plugin.getAdvisors());
        }
        for (PluginDescriptor plugin : Plugins.getInstalledPluginDescriptors()) {
            mixins.addAll(plugin.getMixins());
            advisors.addAll(plugin.getAdvisors());
        }
        return new WeavingClassFileTransformer(Iterables.toArray(mixins, Mixin.class),
                Iterables.toArray(advisors, Advice.class), weavingMetric);
    }

    private static void start(AgentArgs agentArgs) {
        logger.debug("start(): classLoader={}", MainEntryPoint.class.getClassLoader());
        synchronized (lock) {
            if (injector != null) {
                throw new IllegalStateException("Informant is already started");
            }
            injector = Guice.createInjector(new InformantModule(agentArgs, weavingMetric));
            InformantModule.start(injector);
        }
        returnPluginServicesProxy.set(false);
        synchronized (returnPluginServicesProxy) {
            for (PluginServicesProxy proxy : pluginServicesProxies) {
                proxy.start(injector.getInstance(PluginServicesImplFactory.class),
                        injector.getInstance(ConfigService.class));
            }
            // don't need reference to these proxies anymore, may as well clean up
            pluginServicesProxies.clear();
        }
    }

    @OnlyUsedByTests
    public static WeavingMetric getWeavingMetric() {
        return weavingMetric;
    }

    @OnlyUsedByTests
    public static void start(String agentArgs) {
        start(AgentArgs.from(agentArgs));
    }

    @OnlyUsedByTests
    public static int getPort() {
        return injector.getInstance(HttpServer.class).getPort();
    }

    @OnlyUsedByTests
    public static void shutdown() {
        logger.debug("shutdown()");
        synchronized (lock) {
            if (injector == null) {
                throw new IllegalStateException("Informant is not started");
            }
            InformantModule.close(injector);
            injector = null;
        }
    }

    private MainEntryPoint() {}
}
