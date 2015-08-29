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
package org.glowroot;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.instrument.Instrumentation;
import java.nio.channels.FileLock;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import javax.annotation.Nullable;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.AgentModule;
import org.glowroot.agent.ViewerAgentModule;
import org.glowroot.agent.util.SpyingLogbackFilter;
import org.glowroot.collector.spi.Aggregate;
import org.glowroot.collector.spi.Collector;
import org.glowroot.collector.spi.GaugeValue;
import org.glowroot.common.live.LiveAggregateRepository.LiveAggregateRepositoryNop;
import org.glowroot.common.live.LiveThreadDumpService.LiveThreadDumpServiceNop;
import org.glowroot.common.live.LiveTraceRepository.LiveTraceRepositoryNop;
import org.glowroot.common.live.LiveWeavingService.LiveWeavingServiceNop;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.Tickers;
import org.glowroot.local.LocalModule;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.ui.CreateUiModuleBuilder;
import org.glowroot.ui.UiModule;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

@VisibleForTesting
public class GlowrootModule {

    private static final Logger logger = LoggerFactory.getLogger(GlowrootModule.class);

    private final Ticker ticker;
    private final Clock clock;
    // only null in viewer mode
    private final @Nullable ScheduledExecutorService scheduledExecutor;
    private final LocalModule localModule;
    private final @Nullable AgentModule agentModule;
    private final @Nullable ViewerAgentModule viewerAgentModule;
    private final File baseDir;

    private final RandomAccessFile baseDirLockFile;
    private final FileLock baseDirFileLock;

    // this is used by tests to check that no warnings/errors are logged during tests
    private final boolean loggingSpy;

    private final String bindAddress;
    private final String version;

    private volatile @MonotonicNonNull UiModule uiModule;

    GlowrootModule(File baseDir, Map<String, String> properties,
            @Nullable Instrumentation instrumentation, @Nullable File glowrootJarFile,
            String version, boolean viewerModeEnabled, boolean jbossModules) throws Exception {

        loggingSpy = Boolean.valueOf(properties.get("internal.logging.spy"));
        initStaticLoggerState(baseDir, loggingSpy);

        // lock data dir
        File tmpDir = new File(baseDir, "tmp");
        File lockFile = new File(tmpDir, ".lock");
        try {
            Files.createParentDirs(lockFile);
            Files.touch(lockFile);
        } catch (IOException e) {
            throw new BaseDirLockedException(e);
        }
        baseDirLockFile = new RandomAccessFile(lockFile, "rw");
        FileLock baseDirFileLock = baseDirLockFile.getChannel().tryLock();
        if (baseDirFileLock == null) {
            throw new BaseDirLockedException();
        }
        this.baseDirFileLock = baseDirFileLock;
        lockFile.deleteOnExit();

        ticker = Tickers.getTicker();
        clock = Clock.systemClock();

        if (viewerModeEnabled) {
            viewerAgentModule = new ViewerAgentModule(baseDir, glowrootJarFile);
            scheduledExecutor = null;
            agentModule = null;
            localModule = new LocalModule(baseDir, properties, clock, ticker,
                    viewerAgentModule.getConfigService(), null,
                    viewerAgentModule.getLazyPlatformMBeanServer(), viewerModeEnabled);
        } else {
            ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true)
                    .setNameFormat("Glowroot-Background-%d").build();
            scheduledExecutor = Executors.newScheduledThreadPool(2, threadFactory);
            // trace module needs to be started as early as possible, so that weaving will be
            // applied to
            // as many classes as possible
            // in particular, it needs to be started before StorageModule which uses shaded H2,
            // which
            // loads java.sql.DriverManager, which loads 3rd party jdbc drivers found via
            // services/java.sql.Driver, and those drivers need to be woven
            CollectorProxy collectorProxy = new CollectorProxy();
            agentModule = new AgentModule(clock, ticker, collectorProxy, instrumentation, baseDir,
                    glowrootJarFile, scheduledExecutor, jbossModules);
            localModule = new LocalModule(baseDir, properties, clock, ticker,
                    agentModule.getConfigService(), scheduledExecutor,
                    agentModule.getLazyPlatformMBeanServer(), viewerModeEnabled);
            // now inject the real repositories into the proxies
            collectorProxy.setInstance(localModule.getCollector());
            viewerAgentModule = null;
        }

        bindAddress = getBindAddress(properties);
        this.baseDir = baseDir;
        this.version = version;
    }

    void initUiLazy(final Instrumentation instrumentation) {
        // cannot start netty in premain otherwise can crash JVM
        // see https://github.com/netty/netty/issues/3233
        // and https://bugs.openjdk.java.net/browse/JDK-8041920
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    waitForMain(instrumentation);
                    initUi();
                } catch (Throwable t) {
                    logger.error(t.getMessage(), t);
                }
            }
        });
    }

    void initUi() {
        if (agentModule != null) {
            uiModule = new CreateUiModuleBuilder()
                    .ticker(ticker)
                    .clock(clock)
                    .baseDir(baseDir)
                    .liveJvmService(agentModule.getLiveJvmService())
                    .configRepository(localModule.getConfigRepository())
                    .traceRepository(localModule.getTraceRepository())
                    .aggregateRepository(localModule.getAggregateRepository())
                    .gaugeValueRepository(localModule.getGaugeValueRepository())
                    .repoAdmin(localModule.getRepoAdmin())
                    .liveTraceRepository(agentModule.getLiveTraceRepository())
                    .liveThreadDumpService(agentModule.getLiveThreadDumpService())
                    .liveAggregateRepository(agentModule.getLiveAggregateRepository())
                    .liveWeavingService(agentModule.getLiveWeavingService())
                    .bindAddress(bindAddress)
                    .version(version)
                    .pluginDescriptors(agentModule.getPluginDescriptors())
                    .build();
        } else {
            checkNotNull(viewerAgentModule);
            uiModule = new CreateUiModuleBuilder()
                    .ticker(ticker)
                    .clock(clock)
                    .baseDir(baseDir)
                    .liveJvmService(viewerAgentModule.getLiveJvmService())
                    .configRepository(localModule.getConfigRepository())
                    .traceRepository(localModule.getTraceRepository())
                    .aggregateRepository(localModule.getAggregateRepository())
                    .gaugeValueRepository(localModule.getGaugeValueRepository())
                    .repoAdmin(localModule.getRepoAdmin())
                    .liveTraceRepository(new LiveTraceRepositoryNop())
                    .liveThreadDumpService(new LiveThreadDumpServiceNop())
                    .liveAggregateRepository(new LiveAggregateRepositoryNop())
                    .liveWeavingService(new LiveWeavingServiceNop())
                    .bindAddress(bindAddress)
                    .version(version)
                    .pluginDescriptors(viewerAgentModule.getPluginDescriptors())
                    .build();
        }
    }

    private static void waitForMain(Instrumentation instrumentation) throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 60) {
            Thread.sleep(100);
            for (Class<?> clazz : instrumentation.getInitiatedClasses(null)) {
                if (clazz.getName().equals("sun.misc.Launcher")) {
                    return;
                }
            }
        }
        // something has gone wrong
        logger.error("sun.misc.Launcher was never loaded");
    }

    private static void initStaticLoggerState(File baseDir, boolean loggingSpy) {
        if (shouldOverrideLogging()) {
            overrideLogging(baseDir);
        }
        if (loggingSpy) {
            SpyingLogbackFilter.init();
        }
    }

    private static boolean shouldOverrideLogging() {
        // don't override glowroot.logback-test.xml
        return isShaded() && ClassLoader.getSystemResource("glowroot.logback-test.xml") == null;
    }

    private static void overrideLogging(File baseDir) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        try {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            context.reset();
            context.putProperty("glowroot.base.dir", baseDir.getPath());
            File logbackXmlFile = new File(baseDir, "glowroot.logback.xml");
            if (logbackXmlFile.exists()) {
                configurator.doConfigure(logbackXmlFile);
            } else {
                configurator.doConfigure(Resources.getResource("glowroot.logback-override.xml"));
            }
        } catch (JoranException je) {
            // any errors are printed below by StatusPrinter
        }
        StatusPrinter.printInCaseOfErrorsOrWarnings(context);
    }

    private static String getBindAddress(Map<String, String> properties) {
        // empty check to support parameterized script, e.g. -Dglowroot.ui.bind.address=${somevar}
        String bindAddress = properties.get("ui.bind.address");
        if (Strings.isNullOrEmpty(bindAddress)) {
            return "0.0.0.0";
        } else {
            return bindAddress;
        }
    }

    private static boolean isShaded() {
        try {
            Class.forName("org.glowroot.shaded.slf4j.Logger");
            return true;
        } catch (ClassNotFoundException e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            return false;
        }
    }

    @OnlyUsedByTests
    public LocalModule getLocalModule() {
        return localModule;
    }

    @OnlyUsedByTests
    public @Nullable AgentModule getAgentModule() {
        return agentModule;
    }

    @OnlyUsedByTests
    public UiModule getUiModule() throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 60) {
            if (uiModule != null) {
                return uiModule;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("UI Module failed to start");
    }

    @OnlyUsedByTests
    public void reopen() throws Exception {
        initStaticLoggerState(baseDir, loggingSpy);
        // this is not called by viewer
        checkNotNull(agentModule);
        agentModule.reopen();
    }

    @OnlyUsedByTests
    public void close() throws Exception {
        if (uiModule != null) {
            uiModule.close();
        }
        if (agentModule != null) {
            agentModule.close();
        }
        localModule.close();
        if (scheduledExecutor != null) {
            // close scheduled executor last to prevent exceptions due to above modules attempting
            // to use a shutdown executor
            scheduledExecutor.shutdownNow();
        }
        // finally, close logger
        if (shouldOverrideLogging()) {
            ((LoggerContext) LoggerFactory.getILoggerFactory()).reset();
        }
        baseDirFileLock.release();
        baseDirLockFile.close();
    }

    @VisibleForTesting
    @SuppressWarnings("serial")
    public static class StartupFailedException extends Exception {

        private StartupFailedException() {
            super();
        }

        private StartupFailedException(Throwable cause) {
            super(cause);
        }
    }

    @SuppressWarnings("serial")
    static class BaseDirLockedException extends StartupFailedException {

        private BaseDirLockedException() {
            super();
        }

        private BaseDirLockedException(Throwable cause) {
            super(cause);
        }
    }

    @VisibleForTesting
    static class CollectorProxy implements Collector {

        private volatile @MonotonicNonNull Collector instance;

        @Override
        public void collectTrace(org.glowroot.collector.spi.Trace trace) throws Exception {
            if (instance != null) {
                instance.collectTrace(trace);
            }
        }

        @Override
        public void collectAggregates(Map<String, ? extends Aggregate> overallAggregates,
                Map<String, ? extends Map<String, ? extends Aggregate>> transactionAggregates,
                long captureTime) throws Exception {
            if (instance != null) {
                instance.collectAggregates(overallAggregates, transactionAggregates, captureTime);
            }
        }

        @Override
        public void collectGaugeValues(Map<String, ? extends GaugeValue> gaugeValues)
                throws Exception {
            if (instance != null) {
                instance.collectGaugeValues(gaugeValues);
            }
        }

        @VisibleForTesting
        void setInstance(Collector instance) {
            this.instance = instance;
        }
    }
}
