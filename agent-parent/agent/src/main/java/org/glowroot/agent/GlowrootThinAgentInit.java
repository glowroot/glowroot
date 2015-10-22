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
package org.glowroot.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.Lists;

import org.glowroot.agent.util.Tickers;
import org.glowroot.agent.weaving.PreInitializeWeavingClasses;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;

public class GlowrootThinAgentInit implements GlowrootAgentInit {

    private @Nullable AgentModule agentModule;

    @Override
    public void init(File baseDir, Map<String, String> properties,
            @Nullable Instrumentation instrumentation, @Nullable File glowrootJarFile,
            String glowrootVersion, boolean jbossModules, boolean viewerMode) throws Exception {

        if (instrumentation != null) {
            PreInitializeWeavingClasses.preInitializeClasses();
        }

        Ticker ticker = Tickers.getTicker();
        Clock clock = Clock.systemClock();

        Collector collector = loadCustomCollector(baseDir);
        if (collector == null) {
            String host = properties.get("glowroot.collector.host");
            if (Strings.isNullOrEmpty(host)) {
                throw new IllegalStateException("glowroot.collector.host not supplied");
            }
            String portStr = properties.get("glowroot.collector.port");
            if (Strings.isNullOrEmpty(portStr)) {
                throw new IllegalStateException("glowroot.collector.port not supplied");
            }
            int port = Integer.parseInt(portStr);
            collector = new GrpcCollector(host, port);
        }

        agentModule = new AgentModule(clock, ticker, collector, instrumentation, baseDir,
                glowrootJarFile, jbossModules);
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

    @Override
    @OnlyUsedByTests
    public void reopen() throws Exception {
        checkNotNull(agentModule).reopen();
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
}
