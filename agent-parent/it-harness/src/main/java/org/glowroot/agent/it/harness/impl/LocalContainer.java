/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.agent.it.harness.impl;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.MainEntryPoint;
import org.glowroot.agent.init.GlowrootAgentInit;
import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.ConfigService;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TempDirs;
import org.glowroot.agent.weaving.IsolatedWeavingClassLoader;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

public class LocalContainer implements Container {

    private final File baseDir;
    private final boolean deleteBaseDirOnClose;
    private final boolean shared;

    private volatile @Nullable IsolatedWeavingClassLoader isolatedWeavingClassLoader;
    private final @Nullable GrpcServerWrapper server;
    private final @Nullable TraceCollector traceCollector;
    private final @Nullable ConfigService configService;

    private volatile @Nullable AgentBridge agentBridge;

    private volatile @Nullable Thread executingAppThread;

    public static LocalContainer create(File baseDir) throws Exception {
        return new LocalContainer(baseDir, false, false, ImmutableMap.<String, String>of());
    }

    public LocalContainer(@Nullable File baseDir, boolean shared, boolean fat,
            Map<String, String> extraProperties) throws Exception {
        if (baseDir == null) {
            this.baseDir = TempDirs.createTempDir("glowroot-test-basedir");
            deleteBaseDirOnClose = true;
        } else {
            this.baseDir = baseDir;
            deleteBaseDirOnClose = false;
        }
        this.shared = shared;

        int collectorPort;
        if (fat) {
            collectorPort = 0;
            traceCollector = null;
            server = null;
        } else {
            collectorPort = getAvailablePort();
            traceCollector = new TraceCollector();
            server = new GrpcServerWrapper(traceCollector, collectorPort);
        }
        isolatedWeavingClassLoader =
                new IsolatedWeavingClassLoader(AppUnderTest.class, AgentBridge.class);
        AgentBridge agentBridge =
                isolatedWeavingClassLoader.newInstance(AgentBridgeImpl.class, AgentBridge.class);
        Map<String, String> properties = Maps.newHashMap();
        properties.put("glowroot.base.dir", this.baseDir.getAbsolutePath());
        if (collectorPort != 0) {
            properties.put("glowroot.collector.host", "localhost");
            properties.put("glowroot.collector.port", Integer.toString(collectorPort));
        }
        properties.putAll(extraProperties);
        agentBridge.start(properties);
        // this is used to set slowThresholdMillis=0
        agentBridge.resetConfig();
        this.agentBridge = agentBridge;
        configService = new ConfigServiceImpl(server, false);
    }

    @Override
    public ConfigService getConfigService() {
        checkNotNull(configService);
        return configService;
    }

    @Override
    public void addExpectedLogMessage(String loggerName, String partialMessage) {
        checkNotNull(traceCollector);
        traceCollector.addExpectedLogMessage(loggerName, partialMessage);
    }

    @Override
    public Trace execute(Class<? extends AppUnderTest> appClass) throws Exception {
        checkNotNull(traceCollector);
        executeInternal(appClass);
        Trace trace = traceCollector.getCompletedTrace(10, SECONDS);
        traceCollector.clearTrace();
        return trace;
    }

    @Override
    public void executeNoExpectedTrace(Class<? extends AppUnderTest> appClass) throws Exception {
        executeInternal(appClass);
        Thread.sleep(10);
        if (traceCollector != null && traceCollector.hasTrace()) {
            throw new IllegalStateException("Trace was collected when none was expected");
        }
    }

    @Override
    public void interruptAppUnderTest() throws Exception {
        Thread thread = executingAppThread;
        if (thread == null) {
            throw new IllegalStateException("No app currently executing");
        }
        thread.interrupt();
    }

    @Override
    public Trace getCollectedPartialTrace() throws InterruptedException {
        checkNotNull(traceCollector);
        return traceCollector.getPartialTrace(10, SECONDS);
    }

    @Override
    public void checkAndReset() throws Exception {
        checkNotNull(agentBridge);
        agentBridge.resetConfig();
        if (traceCollector != null) {
            traceCollector.checkAndResetLogMessages();
        }
    }

    @Override
    public void close() throws Exception {
        close(false);
    }

    @Override
    public void close(boolean evenIfShared) throws Exception {
        checkNotNull(agentBridge);
        if (shared && !evenIfShared) {
            // this is the shared container and will be closed at the end of the run
            ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory
                    .getLogger(Logger.ROOT_LOGGER_NAME);
            // detaching existing GrpcLogbackAppender so that it won't continue to pick up and
            // report errors that are logged to this Container
            rootLogger.detachAppender("org.glowroot.agent.init.GrpcLogbackAppender");
            return;
        }
        agentBridge.close();
        if (server != null) {
            server.close();
        }
        if (deleteBaseDirOnClose) {
            TempDirs.deleteRecursively(baseDir);
        }
        // release class loader to prevent PermGen OOM during maven test
        isolatedWeavingClassLoader = null;
        agentBridge = null;
    }

    public boolean isClosed() {
        return isolatedWeavingClassLoader == null;
    }

    private void executeInternal(Class<? extends AppUnderTest> appClass) throws Exception {
        IsolatedWeavingClassLoader isolatedWeavingClassLoader = this.isolatedWeavingClassLoader;
        checkNotNull(isolatedWeavingClassLoader);
        ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(isolatedWeavingClassLoader);
        executingAppThread = Thread.currentThread();
        try {
            AppUnderTest app = isolatedWeavingClassLoader.newInstance(appClass, AppUnderTest.class);
            app.executeApp();
        } finally {
            executingAppThread = null;
            Thread.currentThread().setContextClassLoader(previousContextClassLoader);
        }
    }

    static int getAvailablePort() throws IOException {
        ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverSocket.close();
        return port;
    }

    public interface AgentBridge {
        void start(Map<String, String> properties) throws Exception;
        void close() throws Exception;
        void resetConfig() throws Exception;
    }

    public static class AgentBridgeImpl implements AgentBridge {

        @Override
        public void start(final Map<String, String> properties) throws Exception {
            // start up in separate thread to avoid the main thread from being capture by netty
            // ThreadDeathWatcher, which then causes PermGen OOM during maven test
            Executors.newSingleThreadExecutor().submit(new Callable<Void>() {
                @Override
                public @Nullable Void call() throws Exception {
                    MainEntryPoint.start(properties);
                    return null;
                }
            }).get();
        }

        @Override
        public void close() throws Exception {
            GlowrootAgentInit glowrootAgentInit = MainEntryPoint.getGlowrootAgentInit();
            if (glowrootAgentInit != null) {
                glowrootAgentInit.close();
            }
        }

        @Override
        public void resetConfig() throws Exception {
            GlowrootAgentInit glowrootAgentInit = MainEntryPoint.getGlowrootAgentInit();
            if (glowrootAgentInit != null) {
                glowrootAgentInit.getAgentModule().getConfigService().resetAllConfig();
            }
        }
    }
}
