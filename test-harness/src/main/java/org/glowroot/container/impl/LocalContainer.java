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
package org.glowroot.container.impl;

import java.io.File;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;

import org.glowroot.GlowrootModule;
import org.glowroot.MainEntryPoint;
import org.glowroot.common.SpyingLogbackFilter;
import org.glowroot.common.SpyingLogbackFilter.MessageCount;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.AppUnderTestServices;
import org.glowroot.container.Container;
import org.glowroot.container.TempDirs;
import org.glowroot.container.common.HttpClient;
import org.glowroot.container.config.ConfigService;
import org.glowroot.container.config.ConfigService.GetUiPortCommand;
import org.glowroot.container.trace.TraceService;
import org.glowroot.transaction.AdviceCache;
import org.glowroot.weaving.Advice;
import org.glowroot.weaving.IsolatedWeavingClassLoader;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

public class LocalContainer implements Container {

    private final File dataDir;
    private final boolean deleteDataDirOnClose;
    private final boolean shared;

    private final IsolatedWeavingClassLoader isolatedWeavingClassLoader;
    private final HttpClient httpClient;
    private final ConfigService configService;
    private final TraceService traceService;
    private final List<Thread> executingAppThreads = Lists.newCopyOnWriteArrayList();
    private final GlowrootModule glowrootModule;

    public static Container createWithFileDb(File dataDir) throws Exception {
        return new LocalContainer(dataDir, true, 0, false);
    }

    public LocalContainer(@Nullable File dataDir, boolean useFileDb, int port, boolean shared)
            throws Exception {
        if (dataDir == null) {
            this.dataDir = TempDirs.createTempDir("glowroot-test-datadir");
            deleteDataDirOnClose = true;
        } else {
            this.dataDir = dataDir;
            deleteDataDirOnClose = false;
        }
        this.shared = shared;
        File configFile = new File(this.dataDir, "config.json");
        if (!configFile.exists()) {
            Files.write("{\"ui\":{\"port\":" + port + "}}", configFile, Charsets.UTF_8);
        }
        Map<String, String> properties = Maps.newHashMap();
        properties.put("data.dir", this.dataDir.getAbsolutePath());
        properties.put("internal.logging.spy", "true");
        if (!useFileDb) {
            properties.put("internal.h2.memdb", "true");
        }
        try {
            MainEntryPoint.start(properties);
        } catch (org.glowroot.GlowrootModule.StartupFailedException e) {
            throw new StartupFailedException(e);
        }
        JavaagentMain.setTraceStoreThresholdMillisToZero();
        final GlowrootModule glowrootModule = MainEntryPoint.getGlowrootModule();
        checkNotNull(glowrootModule);
        IsolatedWeavingClassLoader.Builder loader = IsolatedWeavingClassLoader.builder();
        AdviceCache adviceCache = glowrootModule.getTransactionModule().getAdviceCache();
        loader.setMixinTypes(adviceCache.getMixinTypes());
        List<Advice> advisors = Lists.newArrayList();
        advisors.addAll(adviceCache.getAdvisors());
        loader.setAdvisors(advisors);
        loader.setWeavingTimerService(
                glowrootModule.getTransactionModule().getWeavingTimerService());
        loader.setMetricWrapperMethods(glowrootModule.getConfigModule().getConfigService()
                .getAdvancedConfig().metricWrapperMethods());
        loader.addBridgeClasses(AppUnderTest.class, AppUnderTestServices.class);
        loader.addExcludePackages("org.glowroot.api", "org.glowroot.advicegen",
                "org.glowroot.collector", "org.glowroot.common", "org.glowroot.config",
                "org.glowroot.jvm", "org.glowroot.local", "org.glowroot.shaded",
                "org.glowroot.transaction", "org.glowroot.weaving");
        isolatedWeavingClassLoader = loader.build();
        httpClient = new HttpClient(glowrootModule.getUiModule().getPort());
        configService = new ConfigService(httpClient, new GetUiPortCommand() {
            @Override
            public int getUiPort() throws Exception {
                // TODO report checker framework issue that checkNotNull needed here
                // in addition to above
                checkNotNull(glowrootModule);
                return glowrootModule.getUiModule().getPort();
            }
        });
        traceService = new TraceService(httpClient);
        this.glowrootModule = glowrootModule;
    }

    @Override
    public ConfigService getConfigService() {
        return configService;
    }

    @Override
    public void addExpectedLogMessage(String loggerName, String partialMessage) {
        SpyingLogbackFilter.addExpectedMessage(loggerName, partialMessage);
    }

    @Override
    public void executeAppUnderTest(Class<? extends AppUnderTest> appClass) throws Exception {
        ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(isolatedWeavingClassLoader);
        executingAppThreads.add(Thread.currentThread());
        try {
            AppUnderTest app = isolatedWeavingClassLoader.newInstance(appClass, AppUnderTest.class);
            app.executeApp();
        } finally {
            executingAppThreads.remove(Thread.currentThread());
            Thread.currentThread().setContextClassLoader(previousContextClassLoader);
        }
        // wait for all traces to be stored
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (traceService.getNumPendingCompleteTransactions() > 0
                && stopwatch.elapsed(SECONDS) < 5) {
            Thread.sleep(10);
        }
    }

    @Override
    public void interruptAppUnderTest() throws Exception {
        for (Thread thread : executingAppThreads) {
            thread.interrupt();
        }
    }

    @Override
    public TraceService getTraceService() {
        return traceService;
    }

    @Override
    public int getUiPort() throws InterruptedException {
        return glowrootModule.getUiModule().getPort();
    }

    @Override
    public void checkAndReset() throws Exception {
        traceService.assertNoActiveTransactions();
        traceService.deleteAll();
        checkAndResetConfigOnly();
    }

    @Override
    public void checkAndResetConfigOnly() throws Exception {
        configService.resetAllConfig();
        // traceStoreThresholdMillis=0 is the default for testing
        configService.setTraceStoreThresholdMillis(0);
        // check and reset log messages
        MessageCount logMessageCount = SpyingLogbackFilter.clearMessages();
        if (logMessageCount.getExpectedCount() > 0) {
            throw new AssertionError("One or more expected messages were not logged");
        }
        if (logMessageCount.getUnexpectedCount() > 0) {
            throw new AssertionError("One or more unexpected messages were logged");
        }
    }

    @Override
    public void close() throws Exception {
        close(false);
    }

    @Override
    public void close(boolean evenIfShared) throws Exception {
        if (shared && !evenIfShared) {
            // this is the shared container and will be closed at the end of the run
            return;
        }
        httpClient.close();
        glowrootModule.close();
        if (deleteDataDirOnClose) {
            TempDirs.deleteRecursively(dataDir);
        }
    }

    // this is used to re-open a shared container after a non-shared container was used
    public void reopen() {
        MainEntryPoint.reopen(glowrootModule);
    }
}
