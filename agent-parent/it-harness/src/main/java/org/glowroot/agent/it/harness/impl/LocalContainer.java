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
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.common.reflect.Reflection;

import org.glowroot.agent.AgentModule;
import org.glowroot.agent.DataDirLocking.BaseDirLockedException;
import org.glowroot.agent.MainEntryPoint;
import org.glowroot.agent.fat.GlowrootModule;
import org.glowroot.agent.impl.AdviceCache;
import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TempDirs;
import org.glowroot.agent.it.harness.admin.AdminService;
import org.glowroot.agent.it.harness.aggregate.AggregateService;
import org.glowroot.agent.it.harness.common.HttpClient;
import org.glowroot.agent.it.harness.config.ConfigService;
import org.glowroot.agent.it.harness.config.ConfigService.GetUiPortCommand;
import org.glowroot.agent.it.harness.trace.TraceService;
import org.glowroot.agent.util.SpyingLogbackFilter;
import org.glowroot.agent.util.SpyingLogbackFilter.MessageCount;
import org.glowroot.agent.weaving.Advice;
import org.glowroot.agent.weaving.IsolatedWeavingClassLoader;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

public class LocalContainer implements Container {

    private final File baseDir;
    private final boolean deleteBaseDirOnClose;
    private final boolean shared;

    private volatile @Nullable IsolatedWeavingClassLoader isolatedWeavingClassLoader;
    private final HttpClient httpClient;
    private final ConfigService configService;
    private final TraceService traceService;
    private final AggregateService aggregateService;
    private final AdminService adminService;
    private final List<Thread> executingAppThreads = Lists.newCopyOnWriteArrayList();
    private final GlowrootModule glowrootModule;

    public static Container createWithFileDb(File baseDir) throws Exception {
        return new LocalContainer(baseDir, true, 0, false, ImmutableMap.<String, String>of());
    }

    public LocalContainer(@Nullable File baseDir, boolean useFileDb, int port, boolean shared,
            Map<String, String> extraProperties) throws Exception {
        if (baseDir == null) {
            this.baseDir = TempDirs.createTempDir("glowroot-test-basedir");
            deleteBaseDirOnClose = true;
        } else {
            this.baseDir = baseDir;
            deleteBaseDirOnClose = false;
        }
        this.shared = shared;
        File configFile = new File(this.baseDir, "config.json");
        if (!configFile.exists()) {
            Files.write("{\"ui\":{\"port\":" + port + "}}", configFile, Charsets.UTF_8);
        }
        Map<String, String> properties = Maps.newHashMap();
        properties.put("base.dir", this.baseDir.getAbsolutePath());
        properties.put("internal.logging.spy", "true");
        if (!useFileDb) {
            properties.put("internal.h2.memdb", "true");
        }
        properties.putAll(extraProperties);
        List<Class<?>> bridgeClasses = ImmutableList.<Class<?>>of(AppUnderTest.class);
        IsolatedClassLoader midLoader = new IsolatedClassLoader(bridgeClasses);
        ClassLoader priorContextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            // need to initialize MainEntryPoint first to give SLF4J a chance to load outside
            // of the context class loader, otherwise often (but not always) end up with this:
            //
            // SLF4J: The following set of substitute loggers may have been accessed
            // SLF4J: during the initialization phase. Logging calls during this
            // SLF4J: phase were not honored. However, subsequent logging calls to these
            // SLF4J: loggers will work as normally expected.
            // SLF4J: See also http://www.slf4j.org/codes.html#substituteLogger
            // SLF4J: org.glowroot.agent.weaving.IsolatedWeavingClassLoader

            Reflection.initialize(MainEntryPoint.class);

            Thread.currentThread().setContextClassLoader(midLoader);
            MainEntryPoint.start(properties);
        } catch (BaseDirLockedException e) {
            throw new StartupFailedException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(priorContextClassLoader);
        }
        JavaagentMain.setTransactionSlowThresholdMillisToZero();
        final GlowrootModule glowrootModule = GlowrootModule.getInstance();
        checkNotNull(glowrootModule);
        IsolatedWeavingClassLoader.Builder loader = IsolatedWeavingClassLoader.builder();
        loader.setParentClassLoader(midLoader);
        AgentModule agentModule = glowrootModule.getAgentModule();
        checkNotNull(agentModule);
        AdviceCache adviceCache = agentModule.getAdviceCache();
        loader.setShimTypes(adviceCache.getShimTypes());
        loader.setMixinTypes(adviceCache.getMixinTypes());
        List<Advice> advisors = Lists.newArrayList();
        advisors.addAll(adviceCache.getAdvisors());
        loader.setAdvisors(advisors);
        loader.setWeavingTimerService(agentModule.getWeavingTimerService());
        loader.setTimerWrapperMethods(
                agentModule.getConfigService().getAdvancedConfig().timerWrapperMethods());
        loader.addBridgeClasses(bridgeClasses);
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
        aggregateService = new AggregateService(httpClient);
        adminService = new AdminService(httpClient);
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
        IsolatedWeavingClassLoader isolatedWeavingClassLoader = this.isolatedWeavingClassLoader;
        if (isolatedWeavingClassLoader == null) {
            throw new AssertionError("LocalContainer has already been stopped");
        }
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
        while (adminService.getNumPendingCompleteTransactions() > 0
                && stopwatch.elapsed(SECONDS) < 15) {
            Thread.sleep(10);
        }
        if (adminService.getNumPendingCompleteTransactions() > 0) {
            throw new IllegalStateException("There are still pending complete transactions");
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
    public AggregateService getAggregateService() {
        return aggregateService;
    }

    @Override
    public AdminService getAdminService() {
        return adminService;
    }

    @Override
    public int getUiPort() throws InterruptedException {
        return glowrootModule.getUiModule().getPort();
    }

    @Override
    public void checkAndReset() throws Exception {
        traceService.assertNoActiveOrPendingCompleteTransactions();
        adminService.deleteAllData();
        checkAndResetConfigOnly();
    }

    @Override
    public void checkAndResetConfigOnly() throws Exception {
        configService.resetAllConfig();
        // setTransactionSlowThresholdMillis=0 is the default for testing
        configService.setTransactionSlowThresholdMillis(0);
        // check and reset log messages
        MessageCount logMessageCount = SpyingLogbackFilter.clearMessages();
        if (logMessageCount.expectedCount() > 0) {
            throw new AssertionError("One or more expected messages were not logged");
        }
        if (logMessageCount.unexpectedCount() > 0) {
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
        if (deleteBaseDirOnClose) {
            TempDirs.deleteRecursively(baseDir);
        }
        // release class loader to prevent OOM Perm Gen
        isolatedWeavingClassLoader = null;
    }

    // this is used to re-open a shared container after a non-shared container was used
    public void reopen() throws Exception {
        glowrootModule.reopen();
    }
}
