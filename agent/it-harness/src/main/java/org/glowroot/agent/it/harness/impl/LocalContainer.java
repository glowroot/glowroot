/*
 * Copyright 2011-2017 the original author or authors.
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
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.reflect.Reflection;

import org.glowroot.agent.MainEntryPoint;
import org.glowroot.agent.init.AgentModule;
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

    static {
        Reflection.initialize(InitLogging.class);
    }

    private final File testDir;
    private final boolean deleteTestDirOnClose;

    private volatile @Nullable IsolatedWeavingClassLoader isolatedWeavingClassLoader;
    private final @Nullable GrpcServerWrapper server;
    private final @Nullable TraceCollector traceCollector;
    private final GlowrootAgentInit glowrootAgentInit;
    private final @Nullable ConfigServiceImpl configService;

    private volatile @Nullable Thread executingAppThread;

    public static LocalContainer create(File testDir) throws Exception {
        return new LocalContainer(testDir, false, ImmutableMap.<String, String>of());
    }

    public LocalContainer(@Nullable File testDir, boolean embedded,
            Map<String, String> extraProperties) throws Exception {
        if (testDir == null) {
            this.testDir = TempDirs.createTempDir("glowroot-test-dir");
            deleteTestDirOnClose = true;
        } else {
            this.testDir = testDir;
            deleteTestDirOnClose = false;
        }

        boolean pointingToCentral = extraProperties.containsKey("glowroot.collector.address");
        final Map<String, String> properties = Maps.newHashMap();
        properties.put("glowroot.test.dir", this.testDir.getAbsolutePath());
        if (embedded || pointingToCentral) {
            traceCollector = null;
            server = null;
        } else {
            int collectorPort = getAvailablePort();
            traceCollector = new TraceCollector();
            server = new GrpcServerWrapper(traceCollector, collectorPort);
            properties.put("glowroot.collector.address",
                    "localhost:" + Integer.toString(collectorPort));
        }
        isolatedWeavingClassLoader = new IsolatedWeavingClassLoader(AppUnderTest.class);
        properties.putAll(extraProperties);
        // start up in separate thread to avoid the main thread from being capture by netty
        // ThreadDeathWatcher, which then causes PermGen OOM during maven test
        Executors.newSingleThreadExecutor().submit(new Callable<Void>() {
            @Override
            public @Nullable Void call() throws Exception {
                AgentModule.isolatedWeavingClassLoader.set(isolatedWeavingClassLoader);
                MainEntryPoint.start(properties);
                return null;
            }
        }).get();
        glowrootAgentInit = checkNotNull(MainEntryPoint.getGlowrootAgentInit());
        if (server == null) {
            configService = null;
            glowrootAgentInit.setSlowThresholdToZero();
        } else {
            configService = new ConfigServiceImpl(server, false);
            // need to set through config service so config service can keep track of changes,
            // otherwise it will clobber slow threshold value on next update through config service
            configService.setSlowThresholdToZero();
        }
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
    public Trace execute(Class<? extends AppUnderTest> appClass, String transactionType)
            throws Exception {
        checkNotNull(traceCollector);
        executeInternal(appClass);
        Trace trace = traceCollector.getCompletedTrace(transactionType, 10, SECONDS);
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
        if (configService == null) {
            glowrootAgentInit.resetConfig();
        } else {
            // need to reset through config service so config service can keep track of changes,
            // otherwise it will clobber the reset config on next update through config service
            configService.resetConfig();
        }
        if (traceCollector != null) {
            traceCollector.checkAndResetLogMessages();
        }
    }

    @Override
    public void close() throws Exception {
        glowrootAgentInit.close();
        if (server != null) {
            server.close();
        }
        glowrootAgentInit.awaitClose();
        if (deleteTestDirOnClose) {
            TempDirs.deleteRecursively(testDir);
        }
        // release class loader to prevent PermGen OOM during maven test
        isolatedWeavingClassLoader = null;
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

    static int getAvailablePort() throws Exception {
        ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverSocket.close();
        return port;
    }
}
