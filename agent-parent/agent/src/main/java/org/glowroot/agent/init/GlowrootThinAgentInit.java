/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.agent.init;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;

import javax.annotation.Nullable;

import ch.qos.logback.core.Context;
import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.PluginCache;
import org.glowroot.agent.util.Tickers;
import org.glowroot.agent.weaving.PreInitializeWeavingClasses;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.Collector;

import static com.google.common.base.Preconditions.checkNotNull;

public class GlowrootThinAgentInit implements GlowrootAgentInit {

    private @Nullable GrpcLogbackAppender grpcAppender;

    private @Nullable AgentModule agentModule;

    @Override
    public void init(final File baseDir, final @Nullable String collectorHost,
            final Map<String, String> properties, final @Nullable Instrumentation instrumentation,
            @Nullable File glowrootJarFile, String glowrootVersion, boolean jbossModules,
            boolean viewerMode) throws Exception {

        if (instrumentation != null) {
            PreInitializeWeavingClasses.preInitializeClasses();
        }

        Ticker ticker = Tickers.getTicker();
        Clock clock = Clock.systemClock();

        PluginCache pluginCache = PluginCache.create(glowrootJarFile, false);
        final ConfigService configService =
                ConfigService.create(baseDir, pluginCache.pluginDescriptors());

        final CollectorProxy collectorProxy = new CollectorProxy();

        grpcAppender = new GrpcLogbackAppender(collectorProxy);
        grpcAppender.setName(GrpcLogbackAppender.class.getName());
        grpcAppender.setContext((Context) LoggerFactory.getILoggerFactory());
        grpcAppender.start();
        attachGrpcAppender(grpcAppender);

        final AgentModule agentModule = new AgentModule(clock, ticker, pluginCache, configService,
                collectorProxy, instrumentation, baseDir, jbossModules);

        NettyWorkaround.run(instrumentation, new Callable</*@Nullable*/Void>() {
            @Override
            public @Nullable Void call() throws Exception {
                Collector customCollector = loadCustomCollector(baseDir);
                if (customCollector != null) {
                    collectorProxy.setInstance(customCollector);
                    return null;
                }
                String serverId = properties.get("glowroot.server.id");
                if (Strings.isNullOrEmpty(serverId)) {
                    serverId = InetAddress.getLocalHost().getHostName();
                }
                String collectorPortStr = properties.get("glowroot.collector.port");
                if (Strings.isNullOrEmpty(collectorPortStr)) {
                    collectorPortStr = System.getProperty("glowroot.collector.port");
                }
                int collectorPort;
                if (Strings.isNullOrEmpty(collectorPortStr)) {
                    collectorPort = 80;
                } else {
                    collectorPort = Integer.parseInt(collectorPortStr);
                }
                // GlowrootThinAgentInit.init() should not be called with null collectorHost
                checkNotNull(collectorHost);
                GrpcCollector grpcCollector =
                        new GrpcCollector(serverId, collectorHost, collectorPort,
                                new ConfigUpdateService(configService,
                                        agentModule.getLiveWeavingService()),
                                agentModule.getLiveJvmService());
                collectorProxy.setInstance(grpcCollector);
                grpcCollector.collectJvmInfo(JvmInfoCreator.create());
                return null;
            }
        });
        this.agentModule = agentModule;
    }

    @Override
    @OnlyUsedByTests
    public AgentModule getAgentModule() {
        return checkNotNull(agentModule);
    }

    @Override
    @OnlyUsedByTests
    public void close() throws Exception {
        checkNotNull(agentModule).close();
    }

    private static @Nullable Collector loadCustomCollector(File baseDir)
            throws MalformedURLException {
        File servicesDir = new File(baseDir, "services");
        if (!servicesDir.exists()) {
            return null;
        }
        if (!servicesDir.isDirectory()) {
            return null;
        }
        File[] files = servicesDir.listFiles();
        if (files == null) {
            return null;
        }
        List<URL> urls = Lists.newArrayList();
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".jar")) {
                urls.add(file.toURI().toURL());
            }
        }
        if (urls.isEmpty()) {
            return null;
        }
        URLClassLoader servicesClassLoader = new URLClassLoader(urls.toArray(new URL[0]));
        ServiceLoader<Collector> serviceLoader =
                ServiceLoader.load(Collector.class, servicesClassLoader);
        Iterator<Collector> i = serviceLoader.iterator();
        if (!i.hasNext()) {
            return null;
        }
        return i.next();
    }

    private static void attachGrpcAppender(GrpcLogbackAppender grpcAppender) {
        ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        // detaching existing GrpcLogbackAppender first is for tests
        rootLogger.detachAppender(GrpcLogbackAppender.class.getName());
        rootLogger.addAppender(grpcAppender);
    }
}
